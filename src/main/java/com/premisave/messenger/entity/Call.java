package com.premisave.messenger.entity;

import com.premisave.messenger.enums.CallKind;
import com.premisave.messenger.enums.CallRole;
import com.premisave.messenger.enums.CallStatus;
import com.premisave.messenger.enums.CallType;
import com.premisave.messenger.enums.ParticipantState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One audio or video call. The call itself runs on the LiveKit server in a room named roomName.
 * This record keeps who was invited, who is in it, who the hosts are and how it ended, which is
 * what the call history and the call line in the chat are built from.
 *
 * It is saved with a version number, so two people joining at the same moment cannot overwrite each other.
 */
@Data
@NoArgsConstructor
@Document(collection = "calls")
@CompoundIndexes({
        @CompoundIndex(name = "calls_chat_idx", def = "{'chatId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "calls_user_idx", def = "{'participants.userId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "calls_status_idx", def = "{'status': 1}")
})
public class Call {

    @Id
    private String id;

    @Version
    private Long version;

    private CallKind kind;
    private CallType type;
    private CallStatus status;

    /** The chat it was started from. Null for a call link. */
    private String chatId;
    private String groupId;

    /** A name for a call link, shown on the join screen. */
    private String title;

    private String initiatorId;

    /** The LiveKit room. */
    @Indexed(unique = true, name = "calls_room_idx")
    private String roomName;

    /** Set for a call link. */
    @Indexed(unique = true, sparse = true, name = "calls_link_idx")
    private String linkCode;
    private LocalDateTime linkExpiresAt;

    /** When true nobody new can join. People already invited or already in can still come back. */
    private boolean locked;

    private int maxParticipants;

    private List<Participant> participants = new ArrayList<>();

    private LocalDateTime createdAt;
    /** When the second person joined. */
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    /** ALL_LEFT, HANGUP, ENDED_BY_HOST, DECLINED, NO_ANSWER, CANCELLED, BUSY, LINK_EXPIRED. */
    private String endReason;
    private String endedBy;

    /** The call line in the chat, updated as the call changes. */
    private String logMessageId;

    /** People who removed this call from their own history. */
    private Set<String> hiddenFor = new HashSet<>();

    // ------------------------------------------------------------- helpers (not saved, not bean properties)

    public Participant participant(String userId) {
        if (userId == null) return null;
        for (Participant p : participants) {
            if (userId.equals(p.getUserId())) return p;
        }
        return null;
    }

    public long joinedCount() {
        return participants.stream().filter(p -> p.getState() == ParticipantState.JOINED).count();
    }

    /** People who are in the call or whose phones are ringing. Counts toward the size limit. */
    public long activeCount() {
        return participants.stream()
                .filter(p -> p.getState() == ParticipantState.JOINED || p.getState() == ParticipantState.INVITED)
                .count();
    }

    public boolean running() {
        return status != null && status.isOpen();
    }

    public Set<String> everyoneIds() {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (Participant p : participants) ids.add(p.getUserId());
        return ids;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Participant {
        private String userId;
        /** Their name and picture as they were when they were added, so the call never needs a lookup to be shown. */
        private String displayName;
        private String profilePictureUrl;
        private ParticipantState state;
        private CallRole role = CallRole.MEMBER;
        private String invitedBy;
        private LocalDateTime invitedAt;
        /** The last time they joined. */
        private LocalDateTime joinedAt;
        private LocalDateTime leftAt;
    }
}
