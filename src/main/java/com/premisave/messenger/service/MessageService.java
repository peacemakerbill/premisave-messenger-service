package com.premisave.messenger.service;

import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import com.premisave.messenger.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Send a new message (called from WebSocket)
     */
    public ChatMessage sendMessage(ChatMessage chatMessage) {
        try {
            Message message = new Message();
            message.setChatId(chatMessage.getChatId());
            message.setSenderId(chatMessage.getSenderId());
            message.setReceiverId(chatMessage.getReceiverId());
            message.setContent(chatMessage.getContent());
            message.setMessageType(chatMessage.getMessageType() != null ? chatMessage.getMessageType() : MessageType.TEXT);
            message.setMediaUrl(chatMessage.getMediaUrl());
            message.setStatus(MessageStatus.SENT);
            message.setCreatedAt(LocalDateTime.now());

            Message savedMessage = messageRepository.save(message);

            // Update last message in chat
            chatService.updateLastMessage(savedMessage.getChatId(), savedMessage.getId());

            // Convert to WebSocket response
            ChatMessage response = convertToChatMessage(savedMessage);

            // Notify receiver in real-time
            if (chatMessage.getReceiverId() != null) {
                messagingTemplate.convertAndSendToUser(
                        chatMessage.getReceiverId(),
                        "/queue/messages",
                        response
                );
            }

            // Notify sender (delivery confirmation)
            messagingTemplate.convertAndSendToUser(
                    chatMessage.getSenderId(),
                    "/queue/messages",
                    response
            );

            log.info("Message sent from {} to {} in chat {}", 
                    savedMessage.getSenderId(), savedMessage.getReceiverId(), savedMessage.getChatId());

            return response;

        } catch (Exception e) {
            log.error("Failed to send message", e);
            throw new RuntimeException("Failed to send message", e);
        }
    }

    /**
     * Get paginated messages for a chat
     */
    public List<MessageResponse> getChatMessages(String chatId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<Message> messages = messageRepository.findByChatIdOrderByCreatedAtDesc(chatId, pageable);

        return messages.stream()
                .map(this::convertToMessageResponse)
                .collect(Collectors.toList());
    }

    /**
     * Mark message as read
     */
    public void markMessageAsRead(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getReadBy().contains(userId)) {
            message.getReadBy().add(userId);
            if (message.getStatus() != MessageStatus.READ) {
                message.setStatus(MessageStatus.READ);
            }
            messageRepository.save(message);

            // Notify sender that message was read
            messagingTemplate.convertAndSendToUser(
                    message.getSenderId(),
                    "/queue/read-receipts",
                    Map.of("messageId", messageId, "status", "READ", "readBy", userId)
            );
        }
    }

    /**
     * Delete message for everyone
     */
    public void deleteMessageForEveryone(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("You can only delete your own messages");
        }

        message.setDeletedForEveryone(true);
        message.setContent("This message was deleted");
        messageRepository.save(message);

        // Notify both users
        messagingTemplate.convertAndSendToUser(message.getSenderId(), "/queue/messages", convertToChatMessage(message));
        if (message.getReceiverId() != null) {
            messagingTemplate.convertAndSendToUser(message.getReceiverId(), "/queue/messages", convertToChatMessage(message));
        }
    }

    private ChatMessage convertToChatMessage(Message message) {
        ChatMessage cm = new ChatMessage();
        cm.setId(message.getId());
        cm.setChatId(message.getChatId());
        cm.setSenderId(message.getSenderId());
        cm.setReceiverId(message.getReceiverId());
        cm.setContent(message.isDeletedForEveryone() ? "This message was deleted" : message.getContent());
        cm.setMessageType(message.getMessageType());
        cm.setMediaUrl(message.getMediaUrl());
        cm.setTimestamp(message.getCreatedAt());
        cm.setStatus(message.getStatus().name());
        return cm;
    }

    private MessageResponse convertToMessageResponse(Message message) {
        MessageResponse response = new MessageResponse();
        response.setId(message.getId());
        response.setChatId(message.getChatId());
        response.setSenderId(message.getSenderId());
        response.setMessageType(message.getMessageType());
        response.setContent(message.isDeletedForEveryone() ? "This message was deleted" : message.getContent());
        response.setMediaUrl(message.getMediaUrl());
        response.setStatus(message.getStatus());
        response.setReadBy(message.getReadBy());
        response.setCreatedAt(message.getCreatedAt());
        response.setDeleted(message.isDeletedForEveryone());
        return response;
    }
}