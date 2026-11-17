package com.premisave.messenger.service;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.dto.request.CreateCallLinkRequest;
import com.premisave.messenger.dto.request.StartCallRequest;
import com.premisave.messenger.dto.response.CallChatResponse;
import com.premisave.messenger.dto.response.CallEvent;
import com.premisave.messenger.dto.response.CallInviteResult;
import com.premisave.messenger.dto.response.CallJoinResponse;
import com.premisave.messenger.dto.response.CallLinkPreviewResponse;
import com.premisave.messenger.dto.response.CallResponse;
import com.premisave.messenger.dto.response.CallSummary;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.entity.Call;
import com.premisave.messenger.entity.CallMessage;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.enums.CallEventType;
import com.premisave.messenger.enums.CallKind;
import com.premisave.messenger.enums.CallRole;
import com.premisave.messenger.enums.CallStatus;
import com.premisave.messenger.enums.CallType;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.enums.ParticipantState;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.CallMessageRepository;
import com.premisave.messenger.repository.CallRepository;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.util.UserNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Audio and video calls: one to one, group, and call links.
 *
 * The call record lives here. The media runs on LiveKit (see LiveKitService): this service decides who may join, hands out
 * the join tokens, rings people, keeps the list of who is in the call, applies host controls (mute, remove, lock) and
 * writes the call into the chat and the history.
 *
 * Every change to a call goes through {@link #mutate}, which retries when two changes collide. The change function may run
 * more than once, so it only changes the call and records what it did. Pushes, notifications and LiveKit requests happen
 * afterwards, once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    static final List<CallStatus> OPEN = List.of(CallStatus.RINGING, CallStatus.ACTIVE, CallStatus.WAITING);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String QUEUE = "/queue/calls";
    private static final int MAX_INVITES_AT_ONCE = 50;
    /** A person who just joined is not judged missing from LiveKit for this long: their phone is still connecting. */
    private static final int CONNECT_GRACE_SECONDS = 45;

    private final CallRepository callRepository;
    private final CallMessageRepository callMessageRepository;
    private final ChatRepository chatRepository;
    private final GroupService groupService;
    private final UserService userService;
    private final MessageService messageService;
    private final UserNotificationService userNotifications;
    private final RedisMessagePublisher publisher;
    private final LiveKitService liveKit;
    private final CallProperties props;
    private final MongoTemplate mongoTemplate;

    // =================================================================== start

    /** Starts a call in a chat. When a call is already running in that chat, joins it instead. */
    public CallJoinResponse start(UserSummaryResponse me, String authToken, StartCallRequest req) {
        requireConfigured();
        Chat chat = chatRepository.findByIdAndIsActiveTrue(req.getChatId())
                .orElseThrow(() -> HttpStatusException.notFound("CHAT_NOT_FOUND", "Chat not found"));
        if (!isChatMember(chat, me.getId())) {
            throw HttpStatusException.forbidden("You are not part of this chat");
        }

        // Two people calling each other at the same moment, or a group call that is already running, end up in one call.
        Optional<Call> running = callRepository.findFirstByChatIdAndStatusInOrderByCreatedAtDesc(chat.getId(), OPEN);
        if (running.isPresent()) {
            return join(me, authToken, running.get().getId());
        }
        requireFree(me.getId(), null);

        boolean group = chat.getChatType() == ChatType.GROUP && chat.getGroupId() != null;
        List<String> invitees = new ArrayList<>();
        if (group) {
            for (String id : groupService.getGroupMemberIds(chat.getGroupId())) {
                if (id != null && !id.equals(me.getId()) && invitees.size() < props.getRingLimit()) invitees.add(id);
            }
        } else {
            for (String id : chat.getParticipantIds()) {
                if (id != null && !id.equals(me.getId())) invitees.add(id);
            }
        }
        if (invitees.isEmpty()) {
            throw HttpStatusException.badRequest("There is nobody to call in this chat");
        }

        LocalDateTime now = LocalDateTime.now();
        Call call = new Call();
        call.setKind(group ? CallKind.GROUP : CallKind.PRIVATE);
        call.setType(req.getType());
        call.setStatus(CallStatus.RINGING);
        call.setChatId(chat.getId());
        call.setGroupId(chat.getGroupId());
        call.setInitiatorId(me.getId());
        call.setRoomName(newRoomName());
        call.setMaxParticipants(props.getMaxParticipants());
        call.setCreatedAt(now);

        Call.Participant caller = newParticipant(me.getId(), UserNames.full(me), me.getProfilePictureUrl(),
                ParticipantState.JOINED, CallRole.HOST, null);
        caller.setJoinedAt(now);
        call.getParticipants().add(caller);

        Map<String, UserSummaryResponse> profiles = profiles(invitees, authToken);
        boolean busy = false;
        for (String id : invitees) {
            UserSummaryResponse u = profiles.get(id);
            Call.Participant p = newParticipant(id, u == null ? null : UserNames.full(u),
                    u == null ? null : u.getProfilePictureUrl(), ParticipantState.INVITED, CallRole.MEMBER, me.getId());
            p.setInvitedAt(now);
            if (!group && isInAnotherCall(id)) {
                // A private call to someone who is already talking to somebody else does not ring.
                p.setState(ParticipantState.MISSED);
                busy = true;
            }
            call.getParticipants().add(p);
        }

        if (busy) {
            call.setStatus(CallStatus.DECLINED);
            call.setEndedAt(now);
            call.setEndReason("BUSY");
            call.setEndedBy(me.getId());
            caller.setState(ParticipantState.LEFT);
            caller.setLeftAt(now);
        }

        Call saved = callRepository.save(call);
        saved = attachLogMessage(saved, authToken);

        if (busy) {
            Set<String> missed = new LinkedHashSet<>(invitees);
            userNotifications.notifyMissedCall(missed, saved.getChatId(), saved.getGroupId(), me.getId(),
                    UserNames.full(me), saved.getType() == CallType.VIDEO);
            CallJoinResponse res = new CallJoinResponse();
            res.setCall(toResponse(saved));
            res.setReason("BUSY");
            return res;
        }

        for (String id : invitees) {
            publish(saved, CallEventType.INCOMING, me.getId(), id, List.of(id));
        }
        return joinResponse(saved, me.getId(), UserNames.full(me), me.getProfilePictureUrl());
    }

    private Call attachLogMessage(Call call, String authToken) {
        if (call.getChatId() == null) return call;
        try {
            String messageId = messageService.postCallLog(call, authToken);
            if (messageId != null) {
                call.setLogMessageId(messageId);
                return callRepository.save(call);
            }
        } catch (Exception e) {
            log.warn("Could not write the call line for call {}: {}", call.getId(), e.getMessage());
        }
        return call;
    }

    // ==================================================================== links

    /** Makes a call link. Nobody is rung. The creator joins with {@link #join} and shares the link. */
    public CallResponse createLink(UserSummaryResponse me, CreateCallLinkRequest req) {
        requireConfigured();
        int max = Math.max(1, props.getLinkTtlHours());
        int hours = req.getExpiresInHours() == null ? max : req.getExpiresInHours();
        if (hours < 1 || hours > max) {
            throw HttpStatusException.badRequest("expiresInHours must be between 1 and " + max);
        }
        String title = req.getTitle() == null ? null : req.getTitle().trim();
        if (title != null && title.length() > 80) title = title.substring(0, 80);
        if (title != null && title.isEmpty()) title = null;

        LocalDateTime now = LocalDateTime.now();
        Call call = new Call();
        call.setKind(CallKind.LINK);
        call.setType(req.getType() == null ? CallType.VIDEO : req.getType());
        call.setStatus(CallStatus.WAITING);
        call.setTitle(title);
        call.setInitiatorId(me.getId());
        call.setRoomName(newRoomName());
        call.setLinkCode(newCode());
        call.setLinkExpiresAt(now.plusHours(hours));
        call.setMaxParticipants(props.getMaxParticipants());
        call.setCreatedAt(now);
        // The creator is listed from the start so the call shows in their history. They are not in it until they join.
        call.getParticipants().add(newParticipant(me.getId(), UserNames.full(me), me.getProfilePictureUrl(),
                ParticipantState.LEFT, CallRole.HOST, null));
        return toResponse(callRepository.save(call));
    }

    public CallLinkPreviewResponse preview(String code) {
        Call call = callRepository.findByLinkCode(code)
                .orElseThrow(() -> HttpStatusException.notFound("CALL_LINK_NOT_FOUND", "This call link does not exist"));
        CallLinkPreviewResponse r = new CallLinkPreviewResponse();
        r.setCallId(call.getId());
        r.setTitle(call.getTitle());
        r.setType(call.getType());
        r.setStatus(call.getStatus());
        Call.Participant host = call.participant(call.getInitiatorId());
        r.setHostName(host == null ? null : host.getDisplayName());
        r.setParticipantCount((int) call.joinedCount());
        r.setLocked(call.isLocked());
        r.setFull(call.joinedCount() >= call.getMaxParticipants());
        r.setJoinable(call.running() && !linkExpired(call));
        return r;
    }

    public CallJoinResponse joinByLink(UserSummaryResponse me, String authToken, String code) {
        Call call = callRepository.findByLinkCode(code)
                .orElseThrow(() -> HttpStatusException.notFound("CALL_LINK_NOT_FOUND", "This call link does not exist"));
        if (!call.running() || linkExpired(call)) {
            throw HttpStatusException.gone("CALL_LINK_EXPIRED", "This call link has expired or the call has ended");
        }
        return doJoin(me, authToken, call.getId(), true);
    }

    private static boolean linkExpired(Call call) {
        return call.getLinkExpiresAt() != null && LocalDateTime.now().isAfter(call.getLinkExpiresAt());
    }

    // ===================================================================== join

    /** Answers a ringing call, or joins a running one (a group call from the chat, or a call someone added you to). */
    public CallJoinResponse join(UserSummaryResponse me, String authToken, String callId) {
        return doJoin(me, authToken, callId, false);
    }

    private CallJoinResponse doJoin(UserSummaryResponse me, String authToken, String callId, boolean viaLink) {
        requireConfigured();
        requireRunning(require(callId));
        requireFree(me.getId(), callId);

        boolean[] becameActive = {false};
        Call saved = mutate(callId, call -> {
            becameActive[0] = false;
            requireRunning(call);
            Call.Participant p = call.participant(me.getId());
            if (p != null && p.getState() == ParticipantState.REMOVED) {
                throw HttpStatusException.forbidden("You were removed from this call");
            }
            if (p == null) {
                if (!viaLink) requireCanSelfJoin(call, me.getId());
                if (call.isLocked()) {
                    throw new HttpStatusException(HttpStatus.FORBIDDEN, "CALL_LOCKED", "This call is locked");
                }
                p = newParticipant(me.getId(), UserNames.full(me), me.getProfilePictureUrl(), ParticipantState.LEFT,
                        me.getId().equals(call.getInitiatorId()) ? CallRole.HOST : CallRole.MEMBER, null);
                call.getParticipants().add(p);
            }
            if (p.getState() != ParticipantState.JOINED && call.joinedCount() >= call.getMaxParticipants()) {
                throw new HttpStatusException(HttpStatus.CONFLICT, "CALL_FULL", "This call is full");
            }
            p.setState(ParticipantState.JOINED);
            p.setJoinedAt(LocalDateTime.now());
            p.setLeftAt(null);
            becameActive[0] = activateIfTogether(call);
            return true;
        });

        publish(saved, CallEventType.PARTICIPANT_JOINED, me.getId(), me.getId(), saved.everyoneIds());
        if (becameActive[0]) messageService.refreshCallLog(saved);

        Call.Participant mine = saved.participant(me.getId());
        return joinResponse(saved, me.getId(), mine == null || mine.getDisplayName() == null ? UserNames.full(me) : mine.getDisplayName(),
                me.getProfilePictureUrl());
    }

    /** When a second person is in the call it counts as started. Returns true when this changed the status to ACTIVE. */
    private static boolean activateIfTogether(Call call) {
        if (call.joinedCount() >= 2 && call.getStatus() != CallStatus.ACTIVE) {
            call.setStatus(CallStatus.ACTIVE);
            if (call.getStartedAt() == null) call.setStartedAt(LocalDateTime.now());
            return true;
        }
        return false;
    }

    private void requireCanSelfJoin(Call call, String userId) {
        if (call.getChatId() != null) {
            Chat chat = chatRepository.findByIdAndIsActiveTrue(call.getChatId()).orElse(null);
            if (chat != null && isChatMember(chat, userId)) return;
        }
        throw new HttpStatusException(HttpStatus.FORBIDDEN, "NOT_INVITED", "You were not invited to this call");
    }

    private CallJoinResponse joinResponse(Call call, String userId, String name, String avatar) {
        Call.Participant p = call.participant(userId);
        CallRole role = p == null || p.getRole() == null ? CallRole.MEMBER : p.getRole();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (avatar != null && !avatar.isBlank()) metadata.put("avatar", avatar);
        metadata.put("role", role.name());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("role", role.name());

        CallJoinResponse res = new CallJoinResponse();
        res.setCall(toResponse(call));
        res.setUrl(props.getLivekitUrl());
        res.setToken(liveKit.joinToken(call.getRoomName(), userId, name, metadata, attributes));
        res.setIdentity(userId);
        res.setRole(role.name());
        return res;
    }

    // ======================================================== decline and ring

    /** Says no to a ringing call. */
    public void decline(UserSummaryResponse me, String callId) {
        Set<String> none = new LinkedHashSet<>();
        boolean[] declined = {false};
        boolean[] ended = {false};
        Call saved = mutate(callId, call -> {
            declined[0] = false;
            ended[0] = false;
            if (!call.running()) return false;
            Call.Participant p = call.participant(me.getId());
            if (p == null) throw HttpStatusException.forbidden("You are not part of this call");
            if (p.getState() != ParticipantState.INVITED) return false;
            p.setState(ParticipantState.DECLINED);
            p.setLeftAt(LocalDateTime.now());
            declined[0] = true;

            boolean nobodyRinging = call.getParticipants().stream().noneMatch(x -> x.getState() == ParticipantState.INVITED);
            if (call.getStatus() == CallStatus.RINGING && nobodyRinging && call.joinedCount() <= 1) {
                if (call.getKind() == CallKind.PRIVATE) {
                    finish(call, CallStatus.DECLINED, "DECLINED", me.getId());
                    ended[0] = true;
                } else {
                    call.setStatus(CallStatus.WAITING);
                }
            }
            return true;
        });
        if (!declined[0]) return;
        if (ended[0]) {
            postEnd(saved, none, me.getId(), true);
        } else {
            publish(saved, CallEventType.PARTICIPANT_DECLINED, me.getId(), me.getId(), saved.everyoneIds());
        }
    }

    /** The app tells us its phone is ringing, so the caller can see "Ringing" instead of "Calling". */
    public void ackRinging(String userId, String callId) {
        Call call = callRepository.findById(callId).orElse(null);
        if (call == null || !call.running()) return;
        Call.Participant p = call.participant(userId);
        if (p == null || p.getState() != ParticipantState.INVITED) return;
        publish(call, CallEventType.RINGING, userId, userId, List.of(call.getInitiatorId()));
    }

    // ===================================================================== leave

    /** Hangs up. Ends the call when it was the last person, or when it was a one to one call. */
    public void leave(String userId, String callId) {
        leaveInternal(userId, callId);
    }

    private void leaveInternal(String userId, String callId) {
        Set<String> missed = new LinkedHashSet<>();
        boolean[] changed = {false};
        boolean[] ended = {false};
        boolean[] newHost = {false};
        Call saved = mutate(callId, call -> {
            missed.clear();
            changed[0] = false;
            ended[0] = false;
            newHost[0] = false;
            if (!call.running()) return false;
            Call.Participant p = call.participant(userId);
            if (p == null || p.getState() != ParticipantState.JOINED) return false;

            p.setState(ParticipantState.LEFT);
            p.setLeftAt(LocalDateTime.now());
            changed[0] = true;

            boolean ringing = call.getStatus() == CallStatus.RINGING;
            long remaining = call.joinedCount();
            if (remaining == 0) {
                if (call.getKind() == CallKind.LINK && !linkExpired(call)) {
                    call.setStatus(CallStatus.WAITING);   // a call link stays open until it expires or a host ends it
                } else {
                    missed.addAll(finish(call, endedStatus(call, ringing), ringing ? "CANCELLED" : "ALL_LEFT", userId));
                    ended[0] = true;
                }
            } else if (call.getKind() == CallKind.PRIVATE && call.getParticipants().size() <= 2
                    && call.getStatus() == CallStatus.ACTIVE) {
                // A one to one call is over when either person hangs up.
                missed.addAll(finish(call, CallStatus.ENDED, "HANGUP", userId));
                ended[0] = true;
            } else if (p.getRole() == CallRole.HOST) {
                newHost[0] = promoteSuccessor(call);
            }
            return true;
        });
        if (!changed[0]) return;
        if (ended[0]) {
            postEnd(saved, missed, userId, true);
            return;
        }
        publish(saved, CallEventType.PARTICIPANT_LEFT, userId, userId, saved.everyoneIds());
        if (newHost[0]) publish(saved, CallEventType.ROLE_CHANGED, userId, null, saved.everyoneIds());
    }

    /** Someone has to run the call. Hands the host role to a co-host, or else to whoever has been in the call longest. */
    private static boolean promoteSuccessor(Call call) {
        List<Call.Participant> inCall = call.getParticipants().stream()
                .filter(x -> x.getState() == ParticipantState.JOINED).toList();
        if (inCall.stream().anyMatch(x -> x.getRole() == CallRole.HOST)) return false;
        Call.Participant next = inCall.stream().filter(x -> x.getRole() == CallRole.COHOST).findFirst()
                .orElseGet(() -> inCall.stream()
                        .min((a, b) -> {
                            if (a.getJoinedAt() == null) return 1;
                            if (b.getJoinedAt() == null) return -1;
                            return a.getJoinedAt().compareTo(b.getJoinedAt());
                        }).orElse(null));
        if (next == null) return false;
        next.setRole(CallRole.HOST);
        return true;
    }

    private static CallStatus endedStatus(Call call, boolean ringing) {
        if (call.getKind() == CallKind.LINK) return CallStatus.ENDED;
        if (ringing) return CallStatus.CANCELLED;
        return call.getStartedAt() == null ? CallStatus.MISSED : CallStatus.ENDED;
    }

    /**
     * Closes the call record: everyone still in it is marked left, everyone still ringing is marked as having missed it.
     * Returns the people who missed it. Only changes the call; {@link #postEnd} does the rest after it is saved.
     */
    private static Set<String> finish(Call call, CallStatus status, String reason, String endedBy) {
        LocalDateTime now = LocalDateTime.now();
        Set<String> missed = new LinkedHashSet<>();
        for (Call.Participant p : call.getParticipants()) {
            if (p.getState() == ParticipantState.INVITED) {
                p.setState(ParticipantState.MISSED);
                missed.add(p.getUserId());
            } else if (p.getState() == ParticipantState.JOINED) {
                p.setState(ParticipantState.LEFT);
                p.setLeftAt(now);
            }
        }
        call.setStatus(status);
        call.setEndedAt(now);
        call.setEndReason(reason);
        call.setEndedBy(endedBy);
        return missed;
    }

    /** Everything that follows a call ending, after it was saved: close the room, tell everyone, update the chat, notify misses. */
    private void postEnd(Call call, Set<String> missed, String actorId, boolean closeRoom) {
        if (closeRoom) liveKit.deleteRoom(call.getRoomName());
        publish(call, CallEventType.ENDED, actorId, null, call.everyoneIds());
        messageService.refreshCallLog(call);
        if (missed != null && !missed.isEmpty() && call.getChatId() != null) {
            Call.Participant caller = call.participant(call.getInitiatorId());
            userNotifications.notifyMissedCall(missed, call.getChatId(), call.getGroupId(), call.getInitiatorId(),
                    caller == null ? null : caller.getDisplayName(), call.getType() == CallType.VIDEO);
        }
    }

    // ====================================================================== end

    /** A host ends the call for everyone. */
    public void end(UserSummaryResponse me, String callId) {
        Set<String> missed = new LinkedHashSet<>();
        boolean[] ended = {false};
        Call saved = mutate(callId, call -> {
            missed.clear();
            ended[0] = false;
            if (!call.running()) return false;
            requireManager(call, me.getId());
            boolean ringing = call.getStatus() == CallStatus.RINGING;
            missed.addAll(finish(call, endedStatus(call, ringing), ringing ? "CANCELLED" : "ENDED_BY_HOST", me.getId()));
            ended[0] = true;
            return true;
        });
        if (ended[0]) postEnd(saved, missed, me.getId(), true);
    }

    // =================================================================== invite

    /** Adds people to a running call and rings them. Anyone in the call can add people they can already reach. */
    public CallInviteResult invite(UserSummaryResponse me, String authToken, String callId, Collection<String> userIds) {
        Call before = require(callId);
        requireRunning(before);
        requireJoined(before, me.getId());

        CallInviteResult result = new CallInviteResult();
        Set<String> reachable = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String id : userIds == null ? List.<String>of() : userIds) {
            if (id == null || id.isBlank() || id.equals(me.getId()) || !seen.add(id)) continue;
            if (seen.size() > MAX_INVITES_AT_ONCE) break;
            if (canReach(before, me.getId(), id)) {
                reachable.add(id);
            } else {
                result.getSkipped().add(new CallInviteResult.Skipped(id, "NOT_REACHABLE"));
            }
        }
        Map<String, UserSummaryResponse> profiles = profiles(reachable, authToken);

        List<String> invited = new ArrayList<>();
        List<CallInviteResult.Skipped> skipped = new ArrayList<>();
        Call saved = mutate(callId, call -> {
            invited.clear();
            skipped.clear();
            requireRunning(call);
            requireJoined(call, me.getId());
            boolean manager = isManager(call, me.getId());
            LocalDateTime now = LocalDateTime.now();
            for (String id : reachable) {
                Call.Participant p = call.participant(id);
                if (p != null && (p.getState() == ParticipantState.JOINED || p.getState() == ParticipantState.INVITED)) {
                    skipped.add(new CallInviteResult.Skipped(id, "ALREADY_IN_CALL"));
                    continue;
                }
                if (p != null && p.getState() == ParticipantState.REMOVED && !manager) {
                    skipped.add(new CallInviteResult.Skipped(id, "REMOVED"));
                    continue;
                }
                if (call.activeCount() >= call.getMaxParticipants()) {
                    skipped.add(new CallInviteResult.Skipped(id, "FULL"));
                    continue;
                }
                if (p == null) {
                    UserSummaryResponse u = profiles.get(id);
                    p = newParticipant(id, u == null ? null : UserNames.full(u), u == null ? null : u.getProfilePictureUrl(),
                            ParticipantState.INVITED, CallRole.MEMBER, me.getId());
                    call.getParticipants().add(p);
                }
                p.setState(ParticipantState.INVITED);
                p.setInvitedBy(me.getId());
                p.setInvitedAt(now);
                p.setLeftAt(null);
                invited.add(id);
            }
            return !invited.isEmpty();
        });

        result.getSkipped().addAll(skipped);
        result.getInvited().addAll(invited);
        result.setCall(toResponse(saved));
        if (!invited.isEmpty()) {
            for (String id : invited) {
                publish(saved, CallEventType.INCOMING, me.getId(), id, List.of(id));
            }
            Set<String> others = new LinkedHashSet<>(saved.everyoneIds());
            others.removeAll(invited);
            for (String id : invited) {
                publish(saved, CallEventType.PARTICIPANT_INVITED, me.getId(), id, others);
            }
        }
        return result;
    }

    /** True when the person can already be reached by the one adding them: same group as the call, or a private chat together. */
    private boolean canReach(Call call, String inviterId, String targetId) {
        if (call.getGroupId() != null && groupService.isUserMemberOfGroup(call.getGroupId(), targetId)) return true;
        return chatRepository.findPrivateChatBetween(inviterId, targetId).isPresent();
    }

    // ============================================================ host controls

    /** Mutes one person's microphone. They can unmute themselves. */
    public void muteParticipant(UserSummaryResponse me, String callId, String targetId) {
        Call call = require(callId);
        requireRunning(call);
        requireManager(call, me.getId());
        Call.Participant target = call.participant(targetId);
        if (target == null || target.getState() != ParticipantState.JOINED) {
            throw HttpStatusException.notFound("PARTICIPANT_NOT_FOUND", "That person is not in the call");
        }
        liveKit.muteAudio(call.getRoomName(), List.of(targetId), null);
        publish(call, CallEventType.MUTED_BY_HOST, me.getId(), targetId, List.of(targetId));
    }

    /** Mutes everyone except the host who asked. */
    public void muteAll(UserSummaryResponse me, String callId) {
        Call call = require(callId);
        requireRunning(call);
        requireManager(call, me.getId());
        liveKit.muteAudio(call.getRoomName(), null, Set.of(me.getId()));
        Set<String> targets = new LinkedHashSet<>();
        for (Call.Participant p : call.getParticipants()) {
            if (p.getState() == ParticipantState.JOINED && !p.getUserId().equals(me.getId())) targets.add(p.getUserId());
        }
        publish(call, CallEventType.MUTED_BY_HOST, me.getId(), null, targets);
    }

    /** Takes someone out of the call. They cannot come back unless a host adds them again. */
    public void removeParticipant(UserSummaryResponse me, String callId, String targetId) {
        if (me.getId().equals(targetId)) {
            throw HttpStatusException.badRequest("Use leave to leave the call yourself");
        }
        Call saved = mutate(callId, call -> {
            requireRunning(call);
            requireManager(call, me.getId());
            Call.Participant caller = call.participant(me.getId());
            Call.Participant target = call.participant(targetId);
            if (target == null) throw HttpStatusException.notFound("PARTICIPANT_NOT_FOUND", "That person is not in the call");
            if (target.getRole() == CallRole.HOST
                    || (target.getRole() == CallRole.COHOST && caller.getRole() != CallRole.HOST)) {
                throw HttpStatusException.forbidden("You cannot remove this person");
            }
            if (target.getState() == ParticipantState.REMOVED) return false;
            target.setState(ParticipantState.REMOVED);
            target.setLeftAt(LocalDateTime.now());
            return true;
        });
        liveKit.removeParticipant(saved.getRoomName(), targetId);
        publish(saved, CallEventType.PARTICIPANT_REMOVED, me.getId(), targetId, saved.everyoneIds());
    }

    /** Makes someone a co-host, an ordinary member again, or hands the host role to them. Only the host can. */
    public void setRole(UserSummaryResponse me, String callId, String targetId, CallRole role) {
        Call saved = mutate(callId, call -> {
            requireRunning(call);
            Call.Participant caller = call.participant(me.getId());
            if (caller == null || caller.getState() != ParticipantState.JOINED || caller.getRole() != CallRole.HOST) {
                throw HttpStatusException.forbidden("Only the host can change roles");
            }
            Call.Participant target = call.participant(targetId);
            if (target == null || target.getState() != ParticipantState.JOINED) {
                throw HttpStatusException.notFound("PARTICIPANT_NOT_FOUND", "That person is not in the call");
            }
            if (target.getRole() == role) return false;
            if (role == CallRole.HOST) caller.setRole(CallRole.COHOST);
            target.setRole(role);
            return true;
        });
        publish(saved, CallEventType.ROLE_CHANGED, me.getId(), targetId, saved.everyoneIds());
    }

    /** A locked call lets in only people who were already invited or already in it. */
    public void setLocked(UserSummaryResponse me, String callId, boolean locked) {
        boolean[] changed = {false};
        Call saved = mutate(callId, call -> {
            changed[0] = false;
            requireRunning(call);
            requireManager(call, me.getId());
            if (call.isLocked() == locked) return false;
            call.setLocked(locked);
            changed[0] = true;
            return true;
        });
        if (changed[0]) publish(saved, CallEventType.LOCK_CHANGED, me.getId(), null, saved.everyoneIds());
    }

    // ================================================================= call chat

    public CallChatResponse sendChat(UserSummaryResponse me, String callId, String text) {
        Call call = require(callId);
        requireRunning(call);
        requireJoined(call, me.getId());
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw HttpStatusException.badRequest("The message is empty");
        if (clean.length() > props.getChatMaxLength()) {
            throw HttpStatusException.badRequest("A message in a call can be at most " + props.getChatMaxLength() + " characters");
        }
        CallMessage m = new CallMessage();
        m.setCallId(callId);
        m.setSenderId(me.getId());
        m.setSenderName(UserNames.full(me));
        m.setText(clean);
        m.setCreatedAt(LocalDateTime.now());
        CallChatResponse response = toChatResponse(callMessageRepository.save(m));

        Set<String> inCall = new LinkedHashSet<>();
        for (Call.Participant p : call.getParticipants()) {
            if (p.getState() == ParticipantState.JOINED) inCall.add(p.getUserId());
        }
        CallEvent event = new CallEvent(CallEventType.CHAT, callId, me.getId(), null, null, response, LocalDateTime.now());
        for (String id : inCall) publisher.convertAndSendToUser(id, QUEUE, event);
        return response;
    }

    public List<CallChatResponse> chat(UserSummaryResponse me, String callId, int limit) {
        Call call = require(callId);
        Call.Participant p = call.participant(me.getId());
        if (p == null || p.getState() == ParticipantState.REMOVED) {
            throw HttpStatusException.forbidden("You are not part of this call");
        }
        int size = Math.max(1, Math.min(limit, 200));
        List<CallChatResponse> page = new ArrayList<>(callMessageRepository
                .findByCallIdOrderByCreatedAtDesc(callId, PageRequest.of(0, size)).stream()
                .map(CallService::toChatResponse).toList());
        Collections.reverse(page);
        return page;
    }

    private static CallChatResponse toChatResponse(CallMessage m) {
        CallChatResponse r = new CallChatResponse();
        r.setId(m.getId());
        r.setCallId(m.getCallId());
        r.setSenderId(m.getSenderId());
        r.setSenderName(m.getSenderName());
        r.setText(m.getText());
        r.setCreatedAt(m.getCreatedAt());
        return r;
    }

    // ============================================================ read and history

    public CallResponse get(String userId, String callId) {
        Call call = require(callId);
        Call.Participant p = call.participant(userId);
        if (p == null) {
            // Someone in the chat can look at a call that is running there, to see whether to join.
            requireCanSelfJoin(call, userId);
        }
        return toResponse(call);
    }

    /** The call running in this chat right now, for the "join" banner. Empty when there is none. */
    public Optional<CallResponse> activeInChat(String userId, String chatId) {
        Chat chat = chatRepository.findByIdAndIsActiveTrue(chatId)
                .orElseThrow(() -> HttpStatusException.notFound("CHAT_NOT_FOUND", "Chat not found"));
        if (!isChatMember(chat, userId)) throw HttpStatusException.forbidden("You are not part of this chat");
        return callRepository.findFirstByChatIdAndStatusInOrderByCreatedAtDesc(chatId, OPEN).map(this::toResponse);
    }

    /** Their calls, newest first. Pass the createdAt of the last one you have to get the next page. */
    public List<CallResponse> history(String userId, int limit, LocalDateTime before) {
        int size = Math.max(1, Math.min(limit, 100));
        LocalDateTime cursor = before == null ? LocalDateTime.now().plusMinutes(1) : before;
        return callRepository.findHistory(userId, cursor, PageRequest.of(0, size)).stream().map(this::toResponse).toList();
    }

    /** Removes one finished call from this person's history only. */
    public void hide(String userId, String callId) {
        Call call = require(callId);
        if (call.participant(userId) == null) throw HttpStatusException.forbidden("You are not part of this call");
        if (call.running()) throw HttpStatusException.conflict("A call that is still running cannot be removed");
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(callId)),
                new Update().addToSet("hiddenFor", userId), Call.class);
    }

    /** Clears this person's whole call history. Calls still running stay. */
    public long clearHistory(String userId) {
        return mongoTemplate.updateMulti(
                Query.query(Criteria.where("participants.userId").is(userId)
                        .and("status").nin(OPEN.stream().map(CallStatus::name).toList())),
                new Update().addToSet("hiddenFor", userId), Call.class).getModifiedCount();
    }

    // ===================================================== LiveKit event intake

    /**
     * A person's phone connected to the media server. Makes sure the record says they are in, which repairs a state that a
     * reconnect or a second device may have knocked out of step.
     */
    public void onLiveKitJoined(String room, String identity) {
        Call call = callRepository.findByRoomName(room).orElse(null);
        if (call == null || !call.running()) return;
        Call.Participant p = call.participant(identity);
        if (p == null || p.getState() == ParticipantState.JOINED || p.getState() == ParticipantState.REMOVED) return;
        markConnected(call.getId(), identity);
    }

    /** A person's phone dropped off the media server (closed the app, lost signal). Treated as hanging up. */
    public void onLiveKitLeft(String room, String identity, long eventEpochSeconds, String reason) {
        if ("DUPLICATE_IDENTITY".equalsIgnoreCase(reason)) return;   // the same person joined from another device
        Call call = callRepository.findByRoomName(room).orElse(null);
        if (call == null || !call.running()) return;
        Call.Participant p = call.participant(identity);
        if (p == null || p.getState() != ParticipantState.JOINED) return;
        // A late "left" for an earlier connection must not undo a join that happened after it.
        if (eventEpochSeconds > 0 && p.getJoinedAt() != null
                && p.getJoinedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond() > eventEpochSeconds) {
            return;
        }
        leaveInternal(identity, call.getId());
    }

    private void markConnected(String callId, String userId) {
        boolean[] becameActive = {false};
        boolean[] changed = {false};
        Call saved = mutate(callId, call -> {
            becameActive[0] = false;
            changed[0] = false;
            if (!call.running()) return false;
            Call.Participant p = call.participant(userId);
            if (p == null || p.getState() == ParticipantState.JOINED || p.getState() == ParticipantState.REMOVED) return false;
            p.setState(ParticipantState.JOINED);
            p.setJoinedAt(LocalDateTime.now());
            p.setLeftAt(null);
            becameActive[0] = activateIfTogether(call);
            changed[0] = true;
            return true;
        });
        if (!changed[0]) return;
        publish(saved, CallEventType.PARTICIPANT_JOINED, userId, userId, saved.everyoneIds());
        if (becameActive[0]) messageService.refreshCallLog(saved);
    }

    // ================================================================ background

    /** Every few seconds: stop ringing phones whose time ran out, and close call links that expired unused. */
    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void expireRinging() {
        if (!props.isConfigured()) return;
        try {
            for (Call c : callRepository.findByStatusIn(OPEN)) {
                try {
                    expireRinging(c.getId());
                } catch (Exception e) {
                    log.warn("Could not check ringing for call {}: {}", c.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Ring check failed: {}", e.getMessage());
        }
    }

    private void expireRinging(String callId) {
        Set<String> missed = new LinkedHashSet<>();
        boolean[] ended = {false};
        boolean[] changed = {false};
        Call saved = mutate(callId, call -> {
            missed.clear();
            ended[0] = false;
            changed[0] = false;
            if (!call.running()) return false;
            LocalDateTime now = LocalDateTime.now();

            if (call.getKind() == CallKind.LINK && call.getStatus() == CallStatus.WAITING && call.joinedCount() == 0
                    && linkExpired(call)) {
                finish(call, CallStatus.ENDED, "LINK_EXPIRED", null);
                ended[0] = true;
                changed[0] = true;
                return true;
            }

            LocalDateTime cutoff = now.minusSeconds(Math.max(5, props.getRingTimeoutSeconds()));
            for (Call.Participant p : call.getParticipants()) {
                if (p.getState() == ParticipantState.INVITED && p.getInvitedAt() != null && p.getInvitedAt().isBefore(cutoff)) {
                    p.setState(ParticipantState.MISSED);
                    missed.add(p.getUserId());
                }
            }
            if (missed.isEmpty()) return false;
            changed[0] = true;

            boolean nobodyRinging = call.getParticipants().stream().noneMatch(x -> x.getState() == ParticipantState.INVITED);
            if (nobodyRinging && call.joinedCount() <= 1 && call.getStatus() == CallStatus.RINGING) {
                if (call.getKind() == CallKind.PRIVATE) {
                    finish(call, CallStatus.MISSED, "NO_ANSWER", null);
                    ended[0] = true;
                } else {
                    call.setStatus(CallStatus.WAITING);   // a group call nobody answered stays open for people to join later
                }
            }
            return true;
        });
        if (!changed[0]) return;
        if (ended[0]) {
            postEnd(saved, missed, null, true);
            return;
        }
        for (String id : missed) {
            publish(saved, CallEventType.PARTICIPANT_MISSED, null, id, saved.everyoneIds());
        }
        if (!missed.isEmpty() && saved.getChatId() != null) {
            Call.Participant caller = saved.participant(saved.getInitiatorId());
            userNotifications.notifyMissedCall(missed, saved.getChatId(), saved.getGroupId(), saved.getInitiatorId(),
                    caller == null ? null : caller.getDisplayName(), saved.getType() == CallType.VIDEO);
        }
    }

    /**
     * Every 20 seconds: compares each running call with what LiveKit says is really in the room. People who vanished
     * (crashed app, lost network, webhooks not set up) are marked as having left, so a call never stays open forever and
     * nobody is stuck "already in a call". Skipped entirely when LiveKit cannot be reached.
     */
    @Scheduled(fixedDelay = 20000, initialDelay = 30000)
    public void reconcileWithLiveKit() {
        if (!props.isConfigured()) return;
        try {
            for (Call c : callRepository.findByStatusIn(OPEN)) {
                try {
                    reconcile(c);
                } catch (Exception e) {
                    log.warn("Could not reconcile call {}: {}", c.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Call reconcile failed: {}", e.getMessage());
        }
    }

    private void reconcile(Call call) {
        boolean anyone = call.getParticipants().stream()
                .anyMatch(p -> p.getState() == ParticipantState.JOINED || p.getState() == ParticipantState.REMOVED || p.getLeftAt() != null);
        if (!anyone) return;
        Set<String> live = liveKit.identitiesInRoom(call.getRoomName());
        if (live == null) return;
        LocalDateTime now = LocalDateTime.now();
        for (Call.Participant p : new ArrayList<>(call.getParticipants())) {
            boolean inRoom = live.contains(p.getUserId());
            if (p.getState() == ParticipantState.JOINED && !inRoom && p.getJoinedAt() != null
                    && p.getJoinedAt().isBefore(now.minusSeconds(CONNECT_GRACE_SECONDS))) {
                leaveInternal(p.getUserId(), call.getId());
            } else if (inRoom && p.getState() == ParticipantState.REMOVED) {
                liveKit.removeParticipant(call.getRoomName(), p.getUserId());
            } else if (inRoom && (p.getState() == ParticipantState.LEFT || p.getState() == ParticipantState.INVITED)
                    && (p.getLeftAt() == null || p.getLeftAt().isBefore(now.minusSeconds(CONNECT_GRACE_SECONDS)))) {
                markConnected(call.getId(), p.getUserId());
            }
        }
    }

    // ================================================================== helpers

    /** Changes a call and saves it, retrying when someone else changed it at the same moment. */
    private Call mutate(String callId, Function<Call, Boolean> change) {
        for (int attempt = 0; ; attempt++) {
            Call call = require(callId);
            boolean changed = Boolean.TRUE.equals(change.apply(call));
            if (!changed) return call;
            try {
                return callRepository.save(call);
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= 4) {
                    throw new HttpStatusException(HttpStatus.CONFLICT, "CALL_BUSY", "The call is changing, try again");
                }
            }
        }
    }

    private Call require(String callId) {
        return callRepository.findById(callId)
                .orElseThrow(() -> HttpStatusException.notFound("CALL_NOT_FOUND", "Call not found"));
    }

    private static void requireRunning(Call call) {
        if (!call.running()) {
            throw HttpStatusException.gone("CALL_ENDED", "This call has ended");
        }
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED",
                    "Calls are not set up on this server yet");
        }
    }

    private static void requireJoined(Call call, String userId) {
        Call.Participant p = call.participant(userId);
        if (p == null || p.getState() != ParticipantState.JOINED) {
            throw HttpStatusException.forbidden("You are not in this call");
        }
    }

    private static boolean isManager(Call call, String userId) {
        Call.Participant p = call.participant(userId);
        return p != null && p.getState() == ParticipantState.JOINED
                && (p.getRole() == CallRole.HOST || p.getRole() == CallRole.COHOST);
    }

    private static void requireManager(Call call, String userId) {
        if (!isManager(call, userId)) {
            throw HttpStatusException.forbidden("Only a host of the call can do that");
        }
    }

    private boolean isChatMember(Chat chat, String userId) {
        if (chat.getChatType() == ChatType.GROUP && chat.getGroupId() != null) {
            return groupService.isUserMemberOfGroup(chat.getGroupId(), userId);
        }
        return chat.getParticipantIds() != null && chat.getParticipantIds().contains(userId);
    }

    /** A person can be in only one call at a time. */
    private void requireFree(String userId, String exceptCallId) {
        for (Call c : callRepository.findOpenCallsOf(userId, OPEN)) {
            if (c.getId().equals(exceptCallId)) continue;
            Call.Participant p = c.participant(userId);
            if (p != null && p.getState() == ParticipantState.JOINED) {
                throw new HttpStatusException(HttpStatus.CONFLICT, "ALREADY_IN_CALL", "You are already in another call");
            }
        }
    }

    private boolean isInAnotherCall(String userId) {
        for (Call c : callRepository.findOpenCallsOf(userId, OPEN)) {
            Call.Participant p = c.participant(userId);
            if (p != null && p.getState() == ParticipantState.JOINED) return true;
        }
        return false;
    }

    private static Call.Participant newParticipant(String userId, String name, String picture, ParticipantState state,
                                                   CallRole role, String invitedBy) {
        Call.Participant p = new Call.Participant();
        p.setUserId(userId);
        p.setDisplayName(name);
        p.setProfilePictureUrl(picture);
        p.setState(state);
        p.setRole(role);
        p.setInvitedBy(invitedBy);
        return p;
    }

    /** Names and pictures for the people being added, looked up side by side. People who cannot be found are left out. */
    private Map<String, UserSummaryResponse> profiles(Collection<String> ids, String authToken) {
        Map<String, UserSummaryResponse> out = new ConcurrentHashMap<>();
        if (ids == null || ids.isEmpty() || authToken == null) return out;
        ids.parallelStream().forEach(id -> {
            try {
                UserSummaryResponse u = userService.getUserSummary(id, authToken);
                if (u != null) out.put(id, u);
            } catch (Exception e) {
                log.debug("Could not look up {} for a call: {}", id, e.getMessage());
            }
        });
        return out;
    }

    private static String newRoomName() {
        return "call-" + UUID.randomUUID();
    }

    private static String newCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Sends one event about this call to each person in recipients. */
    private void publish(Call call, CallEventType type, String actorId, String userId, Collection<String> recipients) {
        if (recipients == null || recipients.isEmpty()) return;
        CallEvent event = new CallEvent(type, call.getId(), actorId, userId, toResponse(call), null, LocalDateTime.now());
        for (String id : new LinkedHashSet<>(recipients)) {
            publisher.convertAndSendToUser(id, QUEUE, event);
        }
    }

    public CallResponse toResponse(Call call) {
        CallResponse r = new CallResponse();
        r.setId(call.getId());
        r.setKind(call.getKind());
        r.setType(call.getType());
        r.setStatus(call.getStatus());
        r.setChatId(call.getChatId());
        r.setGroupId(call.getGroupId());
        r.setTitle(call.getTitle());
        r.setInitiatorId(call.getInitiatorId());
        Call.Participant initiator = call.participant(call.getInitiatorId());
        r.setInitiatorName(initiator == null ? null : initiator.getDisplayName());
        r.setLocked(call.isLocked());
        r.setMaxParticipants(call.getMaxParticipants());
        if (call.getKind() == CallKind.LINK && call.getLinkCode() != null && call.running()) {
            r.setLinkUrl(props.linkFor(call.getLinkCode()));
            r.setLinkExpiresAt(call.getLinkExpiresAt());
        }
        r.setCreatedAt(call.getCreatedAt());
        r.setStartedAt(call.getStartedAt());
        r.setEndedAt(call.getEndedAt());
        r.setDurationSeconds(CallSummary.durationOf(call));
        r.setEndReason(call.getEndReason());
        List<CallResponse.Participant> list = new ArrayList<>();
        for (Call.Participant p : call.getParticipants()) {
            CallResponse.Participant v = new CallResponse.Participant();
            v.setUserId(p.getUserId());
            v.setDisplayName(p.getDisplayName());
            v.setProfilePictureUrl(p.getProfilePictureUrl());
            v.setState(p.getState());
            v.setRole(p.getRole());
            v.setJoinedAt(p.getJoinedAt());
            v.setLeftAt(p.getLeftAt());
            list.add(v);
        }
        r.setParticipants(list);
        return r;
    }
}
