package com.premisave.messenger.service;

import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import com.premisave.messenger.exception.MessageNotFoundException;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Send a new message (supports replies)
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
            message.setActive(true);
            message.setReplyToMessageId(chatMessage.getReplyToMessageId());

            Message savedMessage = messageRepository.save(message);

            chatService.updateLastMessage(savedMessage.getChatId(), savedMessage.getId());

            ChatMessage response = convertToChatMessage(savedMessage);

            // Real-time delivery
            if (chatMessage.getReceiverId() != null) {
                messagingTemplate.convertAndSendToUser(chatMessage.getReceiverId(), "/queue/messages", response);
            }
            messagingTemplate.convertAndSendToUser(chatMessage.getSenderId(), "/queue/messages", response);

            return response;

        } catch (Exception e) {
            log.error("Failed to send message", e);
            throw new RuntimeException("Failed to send message", e);
        }
    }

    /**
     * Get paginated messages
     */
    public List<MessageResponse> getChatMessages(String chatId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<Message> messages = messageRepository.findByChatIdOrderByCreatedAtDesc(chatId, pageable);

        return messages.stream()
                .filter(Message::isActive)
                .map(this::convertToMessageResponse)
                .toList();
    }

    public void markMessageAsRead(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getReadBy().contains(userId)) {
            message.getReadBy().add(userId);
            if (message.getStatus() != MessageStatus.READ) {
                message.setStatus(MessageStatus.READ);
            }
            messageRepository.save(message);

            messagingTemplate.convertAndSendToUser(
                    message.getSenderId(),
                    "/queue/read-receipts",
                    Map.of("messageId", messageId, "status", "READ")
            );
        }
    }

    public void deleteMessageForEveryone(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("Only sender can delete for everyone");
        }

        message.setDeletedForEveryone(true);
        message.setContent("This message was deleted");
        message.setEditedAt(LocalDateTime.now());
        message.setActive(false);
        messageRepository.save(message);

        broadcastDeletedMessage(message);
    }

    public void deleteMessageForMe(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getDeletedForUsers().contains(userId)) {
            message.getDeletedForUsers().add(userId);
            messageRepository.save(message);
            log.info("Message {} deleted for user {}", messageId, userId);
        }
    }

    private void broadcastDeletedMessage(Message message) {
        ChatMessage deletedMsg = convertToChatMessage(message);
        messagingTemplate.convertAndSendToUser(message.getSenderId(), "/queue/messages", deletedMsg);
        if (message.getReceiverId() != null) {
            messagingTemplate.convertAndSendToUser(message.getReceiverId(), "/queue/messages", deletedMsg);
        }
    }

    // ==================== Converters ====================

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
        cm.setReplyToMessageId(message.getReplyToMessageId());
        return cm;
    }

    public MessageResponse convertToMessageResponse(ChatMessage chatMessage) {
        MessageResponse response = new MessageResponse();
        response.setId(chatMessage.getId());
        response.setChatId(chatMessage.getChatId());
        response.setSenderId(chatMessage.getSenderId());
        response.setContent(chatMessage.getContent());
        response.setMessageType(chatMessage.getMessageType());
        response.setMediaUrl(chatMessage.getMediaUrl());
        response.setCreatedAt(chatMessage.getTimestamp());
        response.setStatus(MessageStatus.valueOf(chatMessage.getStatus()));
        response.setReplyToMessageId(chatMessage.getReplyToMessageId());
        return response;
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
        response.setEditedAt(message.getEditedAt());
        response.setDeleted(message.isDeletedForEveryone());
        response.setReplyToMessageId(message.getReplyToMessageId());

        // Build reply preview if this message is a reply
        if (message.getReplyToMessageId() != null) {
            response.setReplyPreview(buildReplyPreview(message.getReplyToMessageId()));
        }

        return response;
    }

    /**
     * Build reply preview
     */
    private MessageResponse.ReplyPreview buildReplyPreview(String originalMessageId) {
        if (originalMessageId == null) return null;

        return messageRepository.findById(originalMessageId)
                .map(original -> {
                    MessageResponse.ReplyPreview preview = new MessageResponse.ReplyPreview();
                    preview.setMessageId(original.getId());
                    preview.setSenderId(original.getSenderId());
                    preview.setContent(truncateForPreview(original.getContent()));
                    preview.setMessageType(original.getMessageType());
                    preview.setMediaUrl(original.getMediaUrl());
                    return preview;
                })
                .orElse(null);
    }

    private String truncateForPreview(String content) {
        if (content == null) return "";
        return content.length() > 80 ? content.substring(0, 77) + "..." : content;
    }
}