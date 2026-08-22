package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.ChatType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChatResponse {

    private String id;
    private ChatType chatType;

    private List<String> participantIds;
    private String groupId;

    private MessageResponse lastMessage;
    private LocalDateTime lastMessageAt;

    private int unreadCount;
    private boolean isOnline;

    // Group specific
    private String groupName;
    private String groupPhotoUrl;
    private String adminId;
    private int memberCount;

    private LocalDateTime lastSeen;   // Last seen of the other user (PRIVATE chats)

    /**
     * Human-readable status message for chat creation, e.g.
     * "Chat created successfully." or "Chat already exists between these users."
     * Only populated by POST /api/chats/private - null on GET /api/chats.
     */
    private String message;

    /**
     * True if this private chat was just created by this request,
     * false if it already existed and was returned as-is.
     * Only meaningful on POST /api/chats/private.
     */
    private boolean newlyCreated;

    /**
     * Full profile of the logged-in user making the request - same shape
     * as otherUser, so the client doesn't need a separate /profile/me
     * call just to render "me" in the chat UI (own avatar, name, etc).
     */
    private UserSummaryResponse currentUser;

    /**
     * Full profile of the other participant, populated for PRIVATE chats
     * only: id, username, email, name, profile picture, online status,
     * and last seen - everything a chat list UI needs to render a row
     * without a separate lookup, similar to WhatsApp/Telegram chat lists.
     *
     * Null for GROUP chats (use groupName/groupPhotoUrl/adminId/memberCount
     * instead).
     */
    private UserSummaryResponse otherUser;
}