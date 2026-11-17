package com.premisave.messenger.dto.response;

import com.premisave.messenger.entity.Call;
import com.premisave.messenger.enums.CallStatus;
import com.premisave.messenger.enums.CallType;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/** The call line inside a chat message: what the bubble shows. */
@Data
public class CallSummary {

    private String callId;
    private CallType type;
    private CallStatus status;
    private String initiatorId;
    private String initiatorName;
    private long durationSeconds;
    private int participantCount;
    private LocalDateTime endedAt;

    public static CallSummary from(Call call) {
        CallSummary s = new CallSummary();
        s.setCallId(call.getId());
        s.setType(call.getType());
        s.setStatus(call.getStatus());
        s.setInitiatorId(call.getInitiatorId());
        Call.Participant initiator = call.participant(call.getInitiatorId());
        s.setInitiatorName(initiator == null ? null : initiator.getDisplayName());
        s.setDurationSeconds(durationOf(call));
        s.setParticipantCount((int) call.getParticipants().stream().filter(p -> p.getJoinedAt() != null).count());
        s.setEndedAt(call.getEndedAt());
        return s;
    }

    /** Seconds from when the call started (the second person joined) to when it ended, or until now while it runs. */
    public static long durationOf(Call call) {
        if (call.getStartedAt() == null) return 0;
        LocalDateTime end = call.getEndedAt() != null ? call.getEndedAt() : LocalDateTime.now();
        return Math.max(0, Duration.between(call.getStartedAt(), end).getSeconds());
    }

    /** The one line that shows in the chat list and in notifications. */
    public static String describe(Call call) {
        String what = call.getType() == CallType.VIDEO ? "video call" : "voice call";
        String cap = call.getType() == CallType.VIDEO ? "Video call" : "Voice call";
        return switch (call.getStatus()) {
            case RINGING -> "Calling (" + what + ")";
            case ACTIVE, WAITING -> "Ongoing " + what;
            case ENDED -> cap + ", " + shortDuration(durationOf(call));
            case MISSED, CANCELLED -> "Missed " + what;
            case DECLINED -> cap + " declined";
        };
    }

    private static String shortDuration(long seconds) {
        if (seconds < 60) return seconds + " sec";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }
}
