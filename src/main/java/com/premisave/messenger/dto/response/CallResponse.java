package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.CallKind;
import com.premisave.messenger.enums.CallRole;
import com.premisave.messenger.enums.CallStatus;
import com.premisave.messenger.enums.CallType;
import com.premisave.messenger.enums.ParticipantState;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** A call, in the history, in the live events and when joining. */
@Data
public class CallResponse {

    private String id;
    private CallKind kind;
    private CallType type;
    private CallStatus status;
    private String chatId;
    private String groupId;
    private String title;
    private String initiatorId;
    private String initiatorName;
    private boolean locked;
    private int maxParticipants;

    /** The shareable link, for a call link. */
    private String linkUrl;
    private LocalDateTime linkExpiresAt;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** From when the second person joined to when it ended. Zero if it never started. */
    private long durationSeconds;
    private String endReason;

    /** Whether recording is switched on by the administrators. When false the app hides all of it. */
    private boolean recordingEnabled;
    /** A recording is starting or running in this call right now. */
    private boolean recording;
    /** How many finished recordings the call has. */
    private int recordingCount;

    private List<Participant> participants;

    @Data
    public static class Participant {
        private String userId;
        private String displayName;
        private String profilePictureUrl;
        private ParticipantState state;
        private CallRole role;
        private LocalDateTime joinedAt;
        private LocalDateTime leftAt;
    }
}
