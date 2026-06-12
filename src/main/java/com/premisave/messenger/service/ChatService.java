package com.premisave.messenger.service;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.ChatResponse;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.repository.ChatRepository;
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
    private final AuthServiceClient authServiceClient;

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

        Chat saved = chatRepository.save(newChat);
        log.info("New private chat created between {} and {}", userId1, userId2);
        return saved.getId();
    }

    public void updateLastMessage(String chatId, String messageId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));
        
        chat.setLastMessageId(messageId);
        chat.setLastMessageAt(LocalDateTime.now());
        chatRepository.save(chat);
    }

    /**
     * Get all chats for current user
     */
    public List<ChatResponse> getUserChats(String userId) {
        List<Chat> chats = chatRepository.findByParticipantIdsContaining(userId);
        
        return chats.stream()
                .map(chat -> convertToChatResponse(chat, userId))
                .toList();
    }

    private ChatResponse convertToChatResponse(Chat chat, String currentUserId) {
        ChatResponse response = new ChatResponse();
        response.setId(chat.getId());
        response.setChatType(chat.getChatType());
        response.setParticipantIds(chat.getParticipantIds());
        response.setLastMessageAt(chat.getLastMessageAt());

        // Get last message
        if (chat.getLastMessageId() != null) {
            messageRepository.findById(chat.getLastMessageId())
                    .ifPresent(msg -> {
                        MessageResponse lastMsg = new MessageResponse();
                        lastMsg.setId(msg.getId());
                        lastMsg.setContent(msg.getContent());
                        lastMsg.setCreatedAt(msg.getCreatedAt());
                        response.setLastMessage(lastMsg);
                    });
        }

        // Count unread messages
        long unread = messageRepository.countByChatIdAndReadByNotContaining(chat.getId(), currentUserId);
        response.setUnreadCount((int) unread);

        return response;
    }

    public void deleteChat(String chatId, String userId) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.getParticipantIds().contains(userId)) {
            throw new RuntimeException("You are not part of this chat");
        }

        chat.setActive(false);
        chatRepository.save(chat);
        log.info("Chat {} marked as deleted by user {}", chatId, userId);
    }
}