package com.premisave.messenger.service;

import com.premisave.messenger.dto.response.ChatResponse;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.exception.ChatNotFoundException;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.repository.GroupRepository;
import com.premisave.messenger.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupService groupService;
    private final PresenceService presenceService;
    private final UserService userService;

    /**
     * Get or create private chat between two users.
     * Always stores the real userId in participantIds, and keeps a
     * userId -> email lookup in participantEmails.
     *
     * If an existing chat is found but is missing email data (e.g. was
     * created before this fix, or previously stored an email instead of
     * an ID), this heals it in place rather than leaving stale data.
     */
    public String getOrCreatePrivateChat(String userId1, String email1, String userId2, String email2) {
        Optional<Chat> existing = chatRepository.findPrivateChatBetween(userId1, userId2);

        if (existing.isPresent()) {
            Chat chat = existing.get();
            boolean needsHealing = healParticipants(chat, userId1, email1, userId2, email2);
            if (needsHealing) {
                chatRepository.save(chat);
                log.info("Healed participant data for existing chat {}", chat.getId());
            }
            return chat.getId();
        }

        Chat newChat = new Chat();
        newChat.setChatType(ChatType.PRIVATE);
        newChat.getParticipantIds().add(userId1);
        newChat.getParticipantIds().add(userId2);
        newChat.getParticipantEmails().put(userId1, email1);
        newChat.getParticipantEmails().put(userId2, email2);
        newChat.setCreatedAt(LocalDateTime.now());
        newChat.setActive(true);

        Chat saved = chatRepository.save(newChat);
        log.info("New private chat created between {} ({}) and {} ({})", userId1, email1, userId2, email2);
        return saved.getId();
    }

    /**
     * Ensures participantIds contains real IDs (not emails) and that
     * participantEmails is fully populated. Returns true if the chat
     * document was modified and needs saving.
     */
    private boolean healParticipants(Chat chat, String userId1, String email1, String userId2, String email2) {
        boolean modified = false;

        // Replace any participant entry that looks like an email (contains "@")
        // with the correct real userId.
        List<String> ids = chat.getParticipantIds();
        for (int i = 0; i < ids.size(); i++) {
            String current = ids.get(i);
            if (current.contains("@")) {
                if (current.equalsIgnoreCase(email1)) {
                    ids.set(i, userId1);
                    modified = true;
                } else if (current.equalsIgnoreCase(email2)) {
                    ids.set(i, userId2);
                    modified = true;
                }
            }
        }

        if (!chat.getParticipantEmails().containsKey(userId1) || chat.getParticipantEmails().get(userId1) == null) {
            chat.getParticipantEmails().put(userId1, email1);
            modified = true;
        }
        if (!chat.getParticipantEmails().containsKey(userId2) || chat.getParticipantEmails().get(userId2) == null) {
            chat.getParticipantEmails().put(userId2, email2);
            modified = true;
        }

        return modified;
    }

    /**
     * Get all chats for current user (Private + Groups)
     *
     * @param userId      the current user's real ID (for filtering/ownership)
     * @param authToken   the caller's raw "Bearer &lt;jwt&gt;" header, forwarded
     *                    to auth-service for enrichment (sender names, other
     *                    participant's full profile). Must be the real token -
     *                    NOT the userId - since it's used for actual Feign auth.
     * @param currentUser the already-resolved profile of the logged-in user
     *                    (fetched once by the controller via /profile/me),
     *                    passed through here to avoid re-fetching it per chat.
     */
    public List<ChatResponse> getUserChats(String userId, String authToken, UserSummaryResponse currentUser) {
        List<Chat> chats = chatRepository.findByParticipantIdsContainingAndIsActiveTrue(userId);

        // Enrich currentUser with their own presence once, reused across all chats.
        var selfPresence = presenceService.getPresence(userId);
        currentUser.setOnline(selfPresence.isOnline());
        currentUser.setLastSeen(selfPresence.getLastSeen());

        return chats.stream()
                .map(chat -> convertToChatResponse(chat, userId, authToken, currentUser))
                .toList();
    }

    /**
     * Convert Chat entity to ChatResponse with proper group details
     */
    private ChatResponse convertToChatResponse(Chat chat, String currentUserId, String authToken, UserSummaryResponse currentUser) {
        ChatResponse response = new ChatResponse();
        response.setId(chat.getId());
        response.setChatType(chat.getChatType());
        response.setParticipantIds(chat.getParticipantIds());
        response.setGroupId(chat.getGroupId());
        response.setLastMessageAt(chat.getLastMessageAt());
        response.setCurrentUser(currentUser);

        // Last Message Preview
        if (chat.getLastMessageId() != null) {
            messageRepository.findById(chat.getLastMessageId())
                    .ifPresent(msg -> {
                        MessageResponse lastMsg = new MessageResponse();
                        lastMsg.setId(msg.getId());
                        lastMsg.setContent(msg.isDeletedForEveryone() ? "This message was deleted" : msg.getContent());
                        lastMsg.setCreatedAt(msg.getCreatedAt());
                        lastMsg.setSenderId(msg.getSenderId());
                        lastMsg.setMessageType(msg.getMessageType());
                        lastMsg.setMediaUrl(msg.getMediaUrl());

                        try {
                            UserSummaryResponse sender = userService.getUserSummary(msg.getSenderId(), authToken);
                            lastMsg.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
                            lastMsg.setSenderProfilePic(sender.getProfilePictureUrl());
                        } catch (Exception e) {
                            lastMsg.setSenderName("Unknown User");
                        }

                        response.setLastMessage(lastMsg);
                    });
        }

        // Unread count
        long unread = messageRepository.countByChatIdAndReadByNotContaining(chat.getId(), currentUserId);
        response.setUnreadCount((int) unread);

        // Private Chat
        if (chat.getChatType() == ChatType.PRIVATE) {
            String otherUserId = chat.getParticipantIds().stream()
                    .filter(id -> !id.equals(currentUserId))
                    .findFirst()
                    .orElse(null);

            if (otherUserId != null) {
                var presence = presenceService.getPresence(otherUserId);
                response.setOnline(presence.isOnline());
                if (presence.getLastSeen() != null) {
                    response.setLastSeen(presence.getLastSeen());
                }

                try {
                    UserSummaryResponse otherUser = userService.getUserSummary(otherUserId, authToken);
                    otherUser.setOnline(presence.isOnline());
                    otherUser.setLastSeen(presence.getLastSeen());
                    response.setOtherUser(otherUser);
                } catch (Exception e) {
                    log.warn("Could not enrich other participant {} for chat {}: {}",
                            otherUserId, chat.getId(), e.getMessage());
                }
            }
        } 
        // Group Chat - Improved handling
        else if (chat.getChatType() == ChatType.GROUP && chat.getGroupId() != null) {
            groupRepository.findById(chat.getGroupId()).ifPresent(group -> {
                response.setGroupName(group.getName());
                response.setGroupPhotoUrl(group.getGroupPhotoUrl());
                response.setAdminId(group.getAdminId());
                response.setMemberCount(group.getMemberIds().size());
            });
        }

        return response;
    }

    /**
     * Check if user can access this chat (used for security validation)
     */
    public boolean canAccessChat(String chatId, String userId) {
        if (chatId == null || userId == null) return false;

        Optional<Chat> chatOpt = chatRepository.findById(chatId);
        if (chatOpt.isEmpty()) return false;

        Chat chat = chatOpt.get();
        if (!chat.isActive()) return false;

        if (chat.getChatType() == ChatType.PRIVATE) {
            return chat.getParticipantIds().contains(userId);
        } else if (chat.getChatType() == ChatType.GROUP && chat.getGroupId() != null) {
            return groupService.isUserMemberOfGroup(chat.getGroupId(), userId);
        }

        return false;
    }

    /**
     * Update last message in chat
     */
    public void updateLastMessage(String chatId, String messageId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ChatNotFoundException("Chat not found: " + chatId));
        
        chat.setLastMessageId(messageId);
        chat.setLastMessageAt(LocalDateTime.now());
        chatRepository.save(chat);
    }

    /**
     * Soft delete / archive chat
     */
    public void deleteChat(String chatId, String userId) {
        Chat chat = chatRepository.findByIdAndIsActiveTrue(chatId)
                .orElseThrow(() -> new ChatNotFoundException("Chat not found with id: " + chatId));

        if (!chat.getParticipantIds().contains(userId)) {
            throw new RuntimeException("You are not a participant of this chat");
        }

        chat.setActive(false);
        chat.setUpdatedAt(LocalDateTime.now());
        chatRepository.save(chat);

        log.info("Chat {} marked as inactive by user {}", chatId, userId);
    }

    /**
     * Check if user is participant in chat
     */
    public boolean isUserInChat(String chatId, String userId) {
        Optional<Chat> chatOpt = chatRepository.findById(chatId);
        return chatOpt.map(chat -> chat.getParticipantIds().contains(userId)).orElse(false);
    }
}