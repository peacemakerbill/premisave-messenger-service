package com.premisave.messenger.service;

import com.premisave.messenger.dto.response.ChatResponse;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Group;
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
    private final PresenceService presenceService;
    private final UserService userService;   // Added for profile enrichment

    /**
     * Get or create private chat between two users
     */
    public String getOrCreatePrivateChat(String userId1, String userId2) {
        Optional<Chat> existing = chatRepository.findPrivateChatBetween(userId1, userId2);
        if (existing.isPresent()) {
            return existing.get().getId();
        }

        Chat newChat = new Chat();
        newChat.setChatType(ChatType.PRIVATE);
        newChat.getParticipantIds().add(userId1);
        newChat.getParticipantIds().add(userId2);
        newChat.setCreatedAt(LocalDateTime.now());
        newChat.setActive(true);

        Chat saved = chatRepository.save(newChat);
        log.info("New private chat created between {} and {}", userId1, userId2);
        return saved.getId();
    }

    /**
     * Get all chats for current user (Private + Groups) - Full WhatsApp style
     */
    public List<ChatResponse> getUserChats(String userId) {
        List<Chat> chats = chatRepository.findByParticipantIdsContainingAndIsActiveTrue(userId);
        
        return chats.stream()
                .map(chat -> convertToChatResponse(chat, userId))
                .toList();
    }

    /**
     * Fully enhanced conversion with all optional sections completed
     */
    private ChatResponse convertToChatResponse(Chat chat, String currentUserId) {
        ChatResponse response = new ChatResponse();
        response.setId(chat.getId());
        response.setChatType(chat.getChatType());
        response.setParticipantIds(chat.getParticipantIds());
        response.setGroupId(chat.getGroupId());
        response.setLastMessageAt(chat.getLastMessageAt());

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

                        // Enrich sender profile for last message
                        try {
                            UserSummaryResponse sender = userService.getUserSummary(msg.getSenderId(), "Bearer " + currentUserId);
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

        // ==================== PRIVATE CHAT ====================
        if (chat.getChatType() == ChatType.PRIVATE) {
            String otherUserId = chat.getParticipantIds().stream()
                    .filter(id -> !id.equals(currentUserId))
                    .findFirst()
                    .orElse(null);

            if (otherUserId != null) {
                var presence = presenceService.getPresence(otherUserId);
                response.setOnline(presence.isOnline());
                
                // Fixed: Added Last Seen support
                if (presence.getLastSeen() != null) {
                    response.setLastSeen(presence.getLastSeen());
                }
            }
        } 
        // ==================== GROUP CHAT (Fully Enhanced) ====================
        else if (chat.getChatType() == ChatType.GROUP && chat.getGroupId() != null) {
            try {
                Group group = groupRepository.findById(chat.getGroupId()).orElse(null);
                if (group != null) {
                    response.setGroupName(group.getName());
                    response.setGroupPhotoUrl(group.getGroupPhotoUrl());
                    response.setAdminId(group.getAdminId());
                }
                response.setMemberCount(chat.getParticipantIds().size());
            } catch (Exception e) {
                log.warn("Could not load full group details for chat {}", chat.getId());
            }
        }

        return response;
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