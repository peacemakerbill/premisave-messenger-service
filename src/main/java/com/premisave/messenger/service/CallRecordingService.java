package com.premisave.messenger.service;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.dto.response.CallEvent;
import com.premisave.messenger.dto.response.CallRecordingResponse;
import com.premisave.messenger.dto.response.CallSettingsResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.entity.Call;
import com.premisave.messenger.entity.CallRecording;
import com.premisave.messenger.enums.CallEventType;
import com.premisave.messenger.enums.CallRole;
import com.premisave.messenger.enums.ParticipantState;
import com.premisave.messenger.enums.RecordingStatus;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.CallRecordingRepository;
import com.premisave.messenger.repository.CallRepository;
import com.premisave.messenger.security.AttachmentUrlSigner;
import com.premisave.messenger.storage.RecordingStorage;
import com.premisave.messenger.util.UserNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recording calls so they can be watched later.
 *
 * The recorder (LiveKit Egress) joins the call's room, mixes everyone into one MP4 and writes it to a folder on the
 * server. This service asks for the recording to start and stop, follows its progress from LiveKit's webhooks, and serves
 * the finished file to the people who were in the call.
 *
 * Who may do what:
 *  - start and stop: a host or co-host who is in the call right now,
 *  - list and watch: anyone who was in the call,
 *  - delete: whoever started the recording, or a host or co-host of that call.
 * Recordings are kept until one of them deletes it. The administrators can switch recording off for everyone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordingService {

    private static final String QUEUE = "/queue/calls";

    private final CallRepository callRepository;
    private final CallRecordingRepository recordings;
    private final CallService callService;
    private final CallSettingsService settings;
    private final LiveKitService liveKit;
    private final CallProperties props;
    private final AttachmentUrlSigner signer;
    private final RedisMessagePublisher publisher;
    private final MongoTemplate mongoTemplate;
    private final RecordingStorage storage;

    /** A recording file and what to call it, for the download endpoint. */
    public record Content(CallRecording recording, Path path) { }

    // ============================================================= start / stop

    /** A host or co-host starts recording the call. */
    public CallRecordingResponse start(UserSummaryResponse me, String callId) {
        requireAvailable();
        storage.requireReady();
        Call call = require(callId);
        if (!call.running()) throw HttpStatusException.gone("CALL_ENDED", "This call has ended");
        requireManager(call, me.getId());
        for (CallRecording r : recordings.findByCallIdOrderByCreatedAtDesc(callId)) {
            if (r.getStatus() != null && r.getStatus().isLive()) {
                throw HttpStatusException.conflict("This call is already being recorded");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        CallRecording rec = new CallRecording();
        rec.setId(UUID.randomUUID().toString());
        rec.setCallId(callId);
        rec.setRoomName(call.getRoomName());
        rec.setStartedBy(me.getId());
        rec.setStartedByName(UserNames.full(me));
        rec.setStatus(RecordingStatus.STARTING);
        rec.setFileName(callId + "/" + rec.getId() + ".mp4");
        rec.setCreatedAt(now);
        rec.setUpdatedAt(now);
        recordings.save(rec);

        String egressId;
        try {
            egressId = liveKit.startRoomRecording(call.getRoomName(), egressPath(rec.getFileName()));
        } catch (HttpStatusException e) {
            fail(rec.getId(), "The recorder could not start");
            throw e;
        }
        // Only set the id: the recorder may already have reported back and changed the status.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(rec.getId())),
                new Update().set("egressId", egressId), CallRecording.class);

        publishChange(call, CallEventType.RECORDING_STARTED, me.getId());
        return toResponse(recordings.findById(rec.getId()).orElse(rec), me.getId(), call);
    }

    /** A host or co-host stops the recording. The file is finished a few seconds later and then becomes watchable. */
    public CallRecordingResponse stop(UserSummaryResponse me, String callId) {
        Call call = require(callId);
        requireManager(call, me.getId());
        CallRecording live = recordings.findByCallIdOrderByCreatedAtDesc(callId).stream()
                .filter(r -> r.getStatus() != null && r.getStatus().isCapturing())
                .findFirst()
                .orElseThrow(() -> HttpStatusException.conflict("This call is not being recorded"));
        if (live.getEgressId() == null) {
            throw HttpStatusException.conflict("The recording is still starting. Try again in a moment.");
        }
        setStatus(live.getId(), RecordingStatus.STOPPING);
        if (!liveKit.stopRecording(live.getEgressId())) {
            log.warn("Recorder did not answer a stop for recording {}, the cleanup job will finish it", live.getId());
        }
        publishChange(call, CallEventType.RECORDING_STOPPED, me.getId());
        return toResponse(recordings.findById(live.getId()).orElse(live), me.getId(), call);
    }

    // ================================================================ read / delete

    /** The recordings of a call, newest first, for anyone who was in it. Empty when recording is switched off. */
    public List<CallRecordingResponse> list(UserSummaryResponse me, String callId) {
        Call call = require(callId);
        requireWasIn(call, me.getId());
        if (!settings.recordingAvailable()) return List.of();
        List<CallRecordingResponse> out = new ArrayList<>();
        for (CallRecording r : recordings.findByCallIdOrderByCreatedAtDesc(callId)) {
            out.add(toResponse(r, me.getId(), call));
        }
        return out;
    }

    /** Deletes a recording and its video file. */
    public void delete(UserSummaryResponse me, String callId, String recordingId) {
        requireAvailable();
        Call call = require(callId);
        requireWasIn(call, me.getId());
        CallRecording rec = recordings.findById(recordingId)
                .filter(r -> callId.equals(r.getCallId()))
                .orElseThrow(() -> HttpStatusException.notFound("RECORDING_NOT_FOUND", "Recording not found"));
        if (!canDelete(call, rec, me.getId())) {
            throw HttpStatusException.forbidden("Only the person who recorded it or a host of the call can delete it");
        }
        if (rec.getStatus() != null && rec.getStatus().isLive()) {
            throw HttpStatusException.conflict("Stop the recording before deleting it");
        }
        deleteFile(rec);
        recordings.deleteById(recordingId);
    }

    /** Opens a recording for download after the signed link was checked. */
    public Content open(String recordingId, Long exp, String sig) {
        if (!settings.recordingAvailable()) {
            throw HttpStatusException.notFound("RECORDING_NOT_FOUND", "Recording not found");
        }
        if (!signer.isRecordingLinkValid(recordingId, exp, sig)) {
            throw HttpStatusException.forbidden("This link is not valid any more. Open the call again to get a new one.");
        }
        CallRecording rec = recordings.findById(recordingId)
                .orElseThrow(() -> HttpStatusException.notFound("RECORDING_NOT_FOUND", "Recording not found"));
        if (rec.getStatus() != RecordingStatus.READY) {
            throw HttpStatusException.notFound("RECORDING_NOT_READY", "This recording is not ready yet");
        }
        Path path = fileOf(rec);
        if (!Files.isRegularFile(path)) {
            throw HttpStatusException.notFound("RECORDING_FILE_MISSING", "The video file of this recording is missing");
        }
        return new Content(rec, path);
    }

    // =============================================================== admin switch

    public CallSettingsResponse settingsFor(UserSummaryResponse me) {
        CallSettingsResponse r = new CallSettingsResponse();
        r.setRecordingEnabled(settings.recordingAvailable());
        r.setCanManage(isAdmin(me));
        RecordingStorage.Status st = storage.status();
        r.setStorageMode(st.mode().name());
        r.setStorageReady(st.ready());
        if (r.isCanManage()) {
            // Paths and reasons are for the administrators only.
            r.setStorageLocation(storage.location());
            r.setStorageProblem(st.problem());
            r.setStorageFreeMb(st.freeMb());
        }
        return r;
    }

    /**
     * An administrator switches recording on or off for everyone. Switching it off finishes every recording that is running
     * (so nothing is lost) and tells everyone in a call at the moment, so the buttons disappear straight away.
     */
    public CallSettingsResponse setEnabled(UserSummaryResponse me, boolean enabled) {
        if (!isAdmin(me)) throw HttpStatusException.forbidden("Only an administrator can change this");
        settings.setRecordingEnabled(enabled, me.getId());
        if (!enabled) {
            for (CallRecording r : recordings.findByStatusIn(List.of(RecordingStatus.STARTING, RecordingStatus.RECORDING))) {
                setStatus(r.getId(), RecordingStatus.STOPPING);
                liveKit.stopRecording(r.getEgressId());
            }
        }
        for (Call call : callRepository.findByStatusIn(CallService.OPEN)) {
            publishChange(call, CallEventType.RECORDING_SETTING, me.getId());
        }
        return settingsFor(me);
    }

    private static boolean isAdmin(UserSummaryResponse me) {
        return me != null && "ADMIN".equalsIgnoreCase(me.getRole());
    }

    // ============================================================ recorder reports

    /**
     * LiveKit tells us how a recording is going (egress_started, egress_updated, egress_ended). {@code info} is the
     * "egressInfo" object of the webhook.
     */
    public void onEgressEvent(Map<?, ?> info) {
        String egressId = text(info.get("egressId"));
        String status = text(info.get("status"));
        String room = text(info.get("roomName"));
        if (egressId == null) return;

        CallRecording rec = recordings.findByEgressId(egressId).orElse(null);
        if (rec == null && room != null) {
            // The report can arrive before start() saved the recorder's id.
            rec = recordings.findFirstByRoomNameAndStatusOrderByCreatedAtDesc(room, RecordingStatus.STARTING).orElse(null);
            if (rec != null && rec.getEgressId() == null) {
                mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(rec.getId())),
                        new Update().set("egressId", egressId), CallRecording.class);
                rec.setEgressId(egressId);
            }
        }
        if (rec == null || rec.getStatus() == RecordingStatus.READY || rec.getStatus() == RecordingStatus.FAILED) return;

        switch (status == null ? "" : status) {
            case "EGRESS_ACTIVE" -> {
                if (rec.getStatus() == RecordingStatus.STARTING) {
                    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(rec.getId())),
                            new Update().set("status", RecordingStatus.RECORDING)
                                    .set("startedAt", LocalDateTime.now()).set("updatedAt", LocalDateTime.now()),
                            CallRecording.class);
                }
            }
            case "EGRESS_ENDING" -> setStatus(rec.getId(), RecordingStatus.STOPPING);
            case "EGRESS_COMPLETE", "EGRESS_LIMIT_REACHED" -> {
                finalizeFromFile(rec, durationOf(info), null);
                publishFinished(rec);
            }
            case "EGRESS_FAILED", "EGRESS_ABORTED" -> {
                String why = text(info.get("error"));
                finalizeFromFile(rec, durationOf(info), why == null || why.isBlank() ? "The recorder stopped unexpectedly" : why);
                publishFinished(rec);
            }
            default -> { }
        }
    }

    /** LiveKit sends the duration of a file as nanoseconds in text. */
    private static long durationOf(Map<?, ?> info) {
        Object results = info.get("fileResults");
        if (results instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            try {
                long nanos = Long.parseLong(String.valueOf(first.get("duration")));
                return Math.max(0, nanos / 1_000_000_000L);
            } catch (NumberFormatException ignored) {
                // fall through to 0
            }
        }
        return 0;
    }

    /** Marks a recording READY when its video file is on disk and not empty, FAILED otherwise. */
    private void finalizeFromFile(CallRecording rec, long reportedSeconds, String reportedError) {
        long size = 0;
        try {
            Path path = fileOf(rec);
            if (Files.isRegularFile(path)) size = Files.size(path);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the recording file of {}: {}", rec.getId(), e.getMessage());
        }
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update().set("endedAt", now).set("updatedAt", now);
        if (size > 0) {
            long seconds = reportedSeconds > 0 ? reportedSeconds
                    : rec.getStartedAt() != null ? Math.max(0, Duration.between(rec.getStartedAt(), now).getSeconds()) : 0;
            update.set("status", RecordingStatus.READY).set("sizeBytes", size).set("durationSeconds", seconds);
            rec.setStatus(RecordingStatus.READY);
        } else {
            String error = reportedError != null ? reportedError
                    : "The video file was not found. Check that the recorder and this service use the same recordings folder.";
            update.set("status", RecordingStatus.FAILED).set("error", error);
            rec.setStatus(RecordingStatus.FAILED);
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(rec.getId())), update, CallRecording.class);
    }

    /**
     * Recordings can get stuck when a report from LiveKit is lost: the call is over but the recording still says it is
     * running, or a stop was never confirmed. Look at the file and settle them.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void settleStuckRecordings() {
        try {
            LocalDateTime now = LocalDateTime.now();
            for (CallRecording rec : recordings.findByStatusIn(
                    List.of(RecordingStatus.STARTING, RecordingStatus.RECORDING, RecordingStatus.STOPPING))) {
                LocalDateTime changed = rec.getUpdatedAt() == null ? rec.getCreatedAt() : rec.getUpdatedAt();
                if (changed == null) continue;
                long quiet = Duration.between(changed, now).getSeconds();
                Call call = callRepository.findById(rec.getCallId()).orElse(null);
                boolean callOver = call == null || !call.running();
                boolean stuck = (rec.getStatus() == RecordingStatus.STARTING && quiet > 120)
                        || (rec.getStatus() == RecordingStatus.STOPPING && quiet > 90)
                        || (callOver && quiet > 60);
                if (!stuck) continue;
                if (rec.getStatus() == RecordingStatus.STARTING) {
                    finalizeFromFile(rec, 0, "The recorder did not start. Check that the recording service is running.");
                } else {
                    finalizeFromFile(rec, 0, null);
                }
                publishFinished(rec);
            }
        } catch (Exception e) {
            log.warn("Could not settle recordings: {}", e.getMessage());
        }
    }

    // ===================================================================== helpers

    private void publishFinished(CallRecording rec) {
        callRepository.findById(rec.getCallId()).ifPresent(call -> publishChange(call, CallEventType.RECORDING_STOPPED, null));
    }

    private void publishChange(Call call, CallEventType type, String actorId) {
        Set<String> to = new LinkedHashSet<>(call.everyoneIds());
        if (to.isEmpty()) return;
        CallEvent event = new CallEvent(type, call.getId(), actorId, null, callService.toResponse(call), null, LocalDateTime.now());
        for (String id : to) publisher.convertAndSendToUser(id, QUEUE, event);
    }

    private void setStatus(String recordingId, RecordingStatus status) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(recordingId)),
                new Update().set("status", status).set("updatedAt", LocalDateTime.now()), CallRecording.class);
    }

    private void fail(String recordingId, String error) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(recordingId)),
                new Update().set("status", RecordingStatus.FAILED).set("error", error)
                        .set("endedAt", LocalDateTime.now()).set("updatedAt", LocalDateTime.now()), CallRecording.class);
    }

    private CallRecordingResponse toResponse(CallRecording r, String viewerId, Call call) {
        CallRecordingResponse out = new CallRecordingResponse();
        out.setId(r.getId());
        out.setCallId(r.getCallId());
        out.setStatus(r.getStatus());
        out.setStartedBy(r.getStartedBy());
        out.setStartedByName(r.getStartedByName());
        out.setCreatedAt(r.getCreatedAt());
        out.setStartedAt(r.getStartedAt());
        out.setEndedAt(r.getEndedAt());
        out.setDurationSeconds(r.getDurationSeconds());
        out.setSizeBytes(r.getSizeBytes());
        out.setError(r.getError());
        out.setCanDelete(canDelete(call, r, viewerId));
        if (r.getStatus() == RecordingStatus.READY) {
            out.setPlayUrl(signer.signedRecordingUrl(r.getId(), Duration.ofHours(Math.max(1, props.getRecordingLinkTtlHours()))));
        }
        return out;
    }

    private static boolean canDelete(Call call, CallRecording rec, String userId) {
        if (userId == null) return false;
        if (userId.equals(rec.getStartedBy())) return true;
        Call.Participant p = call.participant(userId);
        return p != null && p.getState() != ParticipantState.REMOVED
                && (p.getRole() == CallRole.HOST || p.getRole() == CallRole.COHOST);
    }

    private void deleteFile(CallRecording rec) {
        try {
            Path path = fileOf(rec);
            Files.deleteIfExists(path);
            Path folder = path.getParent();
            if (folder != null && Files.isDirectory(folder)) {
                try (var left = Files.list(folder)) {
                    if (left.findAny().isEmpty()) Files.deleteIfExists(folder);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete the file of recording {}: {}", rec.getId(), e.getMessage());
        }
    }

    /** The video file on this machine. Refuses any path that would leave the recordings folder. */
    private Path fileOf(CallRecording rec) {
        Path root = storage.root();
        Path path = root.resolve(rec.getFileName()).normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("Recording path is outside the recordings folder");
        return path;
    }

    private String egressPath(String fileName) {
        String base = props.getRecordingsEgressDir() == null ? "/out" : props.getRecordingsEgressDir().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + fileName;
    }

    private void requireAvailable() {
        if (!settings.recordingAvailable()) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "RECORDING_OFF", "Call recording is switched off");
        }
    }

    private Call require(String callId) {
        return callRepository.findById(callId)
                .orElseThrow(() -> HttpStatusException.notFound("CALL_NOT_FOUND", "Call not found"));
    }

    private static void requireManager(Call call, String userId) {
        Call.Participant p = call.participant(userId);
        boolean manager = p != null && p.getState() == ParticipantState.JOINED
                && (p.getRole() == CallRole.HOST || p.getRole() == CallRole.COHOST);
        if (!manager) throw HttpStatusException.forbidden("Only a host of the call can do that");
    }

    private static void requireWasIn(Call call, String userId) {
        Call.Participant p = call.participant(userId);
        boolean wasIn = p != null && p.getState() != ParticipantState.REMOVED
                && (p.getJoinedAt() != null || p.getState() == ParticipantState.JOINED);
        if (!wasIn) throw HttpStatusException.forbidden("You were not in this call");
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
