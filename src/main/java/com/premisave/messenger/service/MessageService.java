package com.premisave.messenger.service;

import com.premisave.messenger.config.MessengerMetrics;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.enums.MessageDeliveryState;
import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import com.premisave.messenger.exception.MessageNotFoundException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final UserService userService;
    private final RedisMessagePublisher redisMessagePublisher;
    private final MessengerMetrics metrics;

    /**
     * Send message with idempotency guarantee and delivery tracking
     */
    @Transactional
    public MessageResponse sendMessage(ChatMessage chatMessage, String authToken) {
        // Generate idempotency key
        String idempotencyKey = UUID.randomUUID().toString();
        
        // Check if already processed (prevent duplicates)
        var existing = messageRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.warn("Duplicate message send attempt detected. Idempotency Key: {}", idempotencyKey);
            return convertToMessageResponse(existing.get(), authToken);
        }

        // Create message entity
        Message message = new Message();
        message.setIdempotencyKey(idempotencyKey);
        message.setChatId(chatMessage.getChatId());
        message.setSenderId(chatMessage.getSenderId());
        message.setContent(chatMessage.getContent());
        message.setMessageType(chatMessage.getMessageType() != null 
            ? chatMessage.getMessageType() 
            : MessageType.TEXT);
        message.setMediaUrl(chatMessage.getMediaUrl());
        message.setStatus(MessageStatus.SENT);
        message.setCreatedAt(LocalDateTime.now());
        message.setActive(true);
        message.setReplyToMessageId(chatMessage.getReplyToMessageId());
        
        // Mark as pending for delivery
        message.setDeliveryState(MessageDeliveryState.PENDING);
        
        // STEP 1: Persist to database first (DURABILITY GUARANTEE)
        Message savedMessage = messageRepository.save(message);
        log.debug("Message persisted to DB: {} | Idempotency Key: {} | Chat: {}", 
            savedMessage.getId(), idempotencyKey, savedMessage.getChatId());

        // STEP 2: Update delivery state
        savedMessage.setDeliveryState(MessageDeliveryState.DELIVERED_TO_DB);
        messageRepository.save(savedMessage);

        // STEP 3: Update chat's last message
        try {
            chatService.updateLastMessage(savedMessage.getChatId(), savedMessage.getId());
        } catch (Exception e) {
            log.error("Failed to update last message in chat", e);
        }

        // Record metric
        if (metrics != null) {
            metrics.incrementMessagesCreated();
        }

        // STEP 4: Async broadcast with retry (non-blocking)
        broadcastMessageAsync(savedMessage, authToken);

        return convertToMessageResponse(savedMessage, authToken);
    }

    /**
     * Broadcast message to recipients with delivery tracking
     */
    @Transactional
    protected void broadcastMessageAsync(Message message, String authToken) {
        try {
            Chat chat = chatRepository.findById(message.getChatId()).orElse(null);
            if (chat == null) {
                message.setDeliveryState(MessageDeliveryState.FAILED_TO_NOTIFY);
                message.setFailureReason("Chat not found");
                message.setFailedAt(LocalDateTime.now());
                messageRepository.save(message);
                log.error("Chat not found for message {}", message.getId());
                return;
            }

            MessageResponse response = convertToMessageResponse(message, authToken);
            List<Message.DeliveryReceipt> receipts = new ArrayList<>();

            // Determine recipients
            List<String> recipients = determineRecipients(chat, message.getSenderId());
            
            if (recipients.isEmpty()) {
                log.warn("No recipients found for message {} in chat {}", message.getId(), message.getChatId());
                message.setDeliveryState(MessageDeliveryState.NOTIFIED_ALL);
                messageRepository.save(message);
                return;
            }

            boolean allNotified = true;
            int successCount = 0;
            int failureCount = 0;

            // Notify each recipient
            for (String recipientId : recipients) {
                try {
                    redisMessagePublisher.convertAndSendToUser(recipientId, "/queue/messages", response);
                    receipts.add(createReceipt(recipientId, MessageDeliveryState.NOTIFIED_ALL));
                    successCount++;
                    log.debug("Message {} delivered to user {}", message.getId(), recipientId);
                } catch (Exception e) {
                    log.warn("Failed to deliver message {} to user {}: {}", 
                        message.getId(), recipientId, e.getMessage());
                    receipts.add(createReceipt(recipientId, MessageDeliveryState.PARTIALLY_NOTIFIED, e.getMessage()));
                    failureCount++;
                    allNotified = false;
                }
            }

            // Update message delivery state
            message.setReceipts(receipts);
            message.setDeliveredAt(LocalDateTime.now());
            
            if (allNotified) {
                message.setDeliveryState(MessageDeliveryState.NOTIFIED_ALL);
            } else {
                message.setDeliveryState(MessageDeliveryState.PARTIALLY_NOTIFIED);
                if (metrics != null) {
                    metrics.incrementMessageDeliveryPartial();
                }
            }
            
            messageRepository.save(message);

            log.info("Message {} delivery completed. Success: {}/{}, Failures: {}", 
                message.getId(), successCount, recipients.size(), failureCount);

        } catch (Exception e) {
            log.error("Broadcast failed for message {}: {}", message.getId(), e.getMessage(), e);
            message.setDeliveryState(MessageDeliveryState.FAILED_TO_NOTIFY);
            message.setFailureReason(e.getMessage());
            message.setFailedAt(LocalDateTime.now());
            messageRepository.save(message);
            if (metrics != null) {
                metrics.incrementMessageDeliveryFailed();
            }
        }
    }

    /**
     * Get messages for a chat with pagination
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getChatMessages(String chatId, int page, int size, String authToken) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<Message> messages = messageRepository.findByChatIdOrderByCreatedAtDesc(chatId, pageable);

        return messages.stream()
            .filter(Message::isActive)
            .map(msg -> convertToMessageResponse(msg, authToken))
            .toList();
    }

    /**
     * Mark message as read with optimistic locking retry
     */
    @Transactional
    public void markMessageAsRead(String messageId, String userId) {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                Message message = messageRepository.findById(messageId)
                    .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

                if (!message.getReadBy().contains(userId)) {
                    message.getReadBy().add(userId);
                    if (message.getStatus() != MessageStatus.READ) {
                        message.setStatus(MessageStatus.READ);
                    }
                    messageRepository.save(message);
                    log.debug("Message {} marked as read by {}", messageId, userId);
                }

                // Notify sender of read receipt
                redisMessagePublisher.convertAndSendToUser(
                    message.getSenderId(),
                    "/queue/read-receipts",
                    Map.of(
                        "messageId", messageId,
                        "readBy", userId,
                        "timestamp", LocalDateTime.now().toString()
                    )
                );
                return;

            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                attempt++;
                if (attempt >= maxRetries) {
                    log.error("Failed to mark message as read after {} attempts", maxRetries, e);
                    throw e;
                }
                log.warn("Optimistic lock conflict, retrying ({}/{})", attempt, maxRetries);
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Delete message for everyone
     */
    @Transactional
    public void deleteMessageForEveryone(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("Only sender can delete message for everyone");
        }

        message.setDeletedForEveryone(true);
        message.setContent("This message was deleted");
        message.setEditedAt(LocalDateTime.now());
        message.setActive(false);
        messageRepository.save(message);

        // Notify all participants
        MessageResponse deletedResponse = convertToMessageResponse(message, "Bearer " + userId);
        broadcastMessage(message, deletedResponse);

        log.info("Message {} deleted for everyone by {}", messageId, userId);
        if (metrics != null) {
            metrics.incrementMessagesDeleted();
        }
    }

    /**
     * Delete message for current user only
     */
    @Transactional
    public void deleteMessageForMe(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getDeletedForUsers().contains(userId)) {
            message.getDeletedForUsers().add(userId);
            messageRepository.save(message);
            log.debug("Message {} deleted for user {}", messageId, userId);
        }
    }

    /**
     * Retry failed messages
     */
    @Transactional
    public void retryFailedMessages() {
        List<Message> failedMessages = messageRepository.findMessagesToRetry();
        
        if (failedMessages.isEmpty()) {
            log.debug("No messages to retry");
            return;
        }

        log.info("Found {} messages to retry", failedMessages.size());
        
        for (Message message : failedMessages) {
            if (message.getRetryCount() < 3) {
                try {
                    message.setRetryCount(message.getRetryCount() + 1);
                    broadcastMessageAsync(message, null);
                    log.info("Retried message {} (attempt {}/3)", message.getId(), message.getRetryCount());
                } catch (Exception e) {
                    log.error("Retry failed for message {}", message.getId(), e);
                }
            } else {
                log.warn("Message {} exceeded max retries (3), marking as failed", message.getId());
                message.setDeliveryState(MessageDeliveryState.FAILED);
                message.setFailedAt(LocalDateTime.now());
                message.setFailureReason("Max retries exceeded");
                messageRepository.save(message);
            }
        }
    }

    // ===== HELPER METHODS =====

    private void broadcastMessage(Message message, MessageResponse response) {
        Chat chat = chatRepository.findById(message.getChatId()).orElse(null);
        if (chat == null) {
            redisMessagePublisher.convertAndSendToUser(message.getSenderId(), "/queue/messages", response);
            return;
        }

        if (chat.getChatType() == ChatType.PRIVATE) {
            chat.getParticipantIds().stream()
                .filter(id -> !id.equals(message.getSenderId()))
                .findFirst()
                .ifPresent(receiver -> redisMessagePublisher.convertAndSendToUser(receiver, "/queue/messages", response));

            redisMessagePublisher.convertAndSendToUser(message.getSenderId(), "/queue/messages", response);
        } else {
            // Group chat
            chat.getParticipantIds().forEach(memberId ->
                redisMessagePublisher.convertAndSendToUser(memberId, "/queue/messages", response)
            );
        }
    }

    private List<String> determineRecipients(Chat chat, String senderId) {
        if (chat.getChatType() == ChatType.PRIVATE) {
            return chat.getParticipantIds().stream()
                .filter(id -> !id.equals(senderId))
                .toList();
        } else {
            return chat.getParticipantIds();
        }
    }

    private Message.DeliveryReceipt createReceipt(String recipientId, MessageDeliveryState state) {
        return createReceipt(recipientId, state, null);
    }

    private Message.DeliveryReceipt createReceipt(String recipientId, MessageDeliveryState state, String failureReason) {
        Message.DeliveryReceipt receipt = new Message.DeliveryReceipt();
        receipt.setRecipientId(recipientId);
        receipt.setState(state);
        receipt.setDeliveredAt(LocalDateTime.now());
        receipt.setFailureReason(failureReason);
        return receipt;
    }

    private MessageResponse convertToMessageResponse(Message message, String authToken) {
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

        if (authToken == null || authToken.isBlank()) {
            // No real user token available - this happens for the
            // background retry job (retryFailedMessages), which runs
            // outside any HTTP request context and has no JWT to use.
            // Skip the enrichment call entirely rather than making a
            // Feign request that's guaranteed to fail.
            response.setSenderName("Unknown User");
        } else {
            try {
                UserSummaryResponse sender = userService.getUserSummary(message.getSenderId(), authToken);
                response.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
                response.setSenderProfilePic(sender.getProfilePictureUrl());
            } catch (Exception e) {
                log.warn("Failed to fetch sender details for {}", message.getSenderId());
                response.setSenderName("Unknown User");
            }
        }

        if (message.getReplyToMessageId() != null) {
            response.setReplyPreview(buildReplyPreview(message.getReplyToMessageId(), authToken));
        }

        return response;
    }

    private MessageResponse.ReplyPreview buildReplyPreview(String originalMessageId, String authToken) {
        return messageRepository.findById(originalMessageId)
            .map(original -> {
                MessageResponse.ReplyPreview preview = new MessageResponse.ReplyPreview();
                preview.setMessageId(original.getId());
                preview.setSenderId(original.getSenderId());
                preview.setContent(truncateForPreview(original.getContent()));
                preview.setMessageType(original.getMessageType());
                preview.setMediaUrl(original.getMediaUrl());

                try {
                    UserSummaryResponse sender = userService.getUserSummary(original.getSenderId(), authToken);
                    preview.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
                } catch (Exception ignored) {
                }

                return preview;
            })
            .orElse(null);
    }

    private String truncateForPreview(String content) {
        if (content == null) return "";
        return content.length() > 80 ? content.substring(0, 77) + "..." : content;
    }
}