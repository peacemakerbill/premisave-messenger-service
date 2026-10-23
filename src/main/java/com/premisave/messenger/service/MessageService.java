package com.premisave.messenger.service;

import com.premisave.messenger.config.MessengerMetrics;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.entity.Attachment;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.enums.MessageDeliveryState;
import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.exception.MessageNotFoundException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final AttachmentService attachmentService;

    /** Hours a sender may edit a message after sending it. 0 turns editing off. */
    @Value("${message.edit-window-hours:1}")
    private long editWindowHours;

    /**
     * Send message with idempotency guarantee and delivery tracking.
     *
     * A message may carry a file by naming an attachmentId from
     * POST /api/attachments. The upload is claimed atomically (only the
     * uploader, only for the chat it was uploaded to, only once), and the
     * message takes its type, file name, size and link from the stored
     * upload rather than from anything the client says about it.
     */
    @Transactional
    public MessageResponse sendMessage(ChatMessage chatMessage, String authToken) {
        authorizeSend(chatMessage);

        boolean hasAttachment = chatMessage.getAttachmentId() != null && !chatMessage.getAttachmentId().isBlank();
        boolean hasText = chatMessage.getContent() != null && !chatMessage.getContent().isBlank();
        if (!hasAttachment && !hasText && chatMessage.getMediaUrl() == null) {
            throw new IllegalArgumentException("A message needs text or an attachment");
        }

        Attachment attachment = hasAttachment
                ? attachmentService.claim(chatMessage.getAttachmentId(), chatMessage.getSenderId(), chatMessage.getChatId())
                : null;

        try {
            return doSend(chatMessage, authToken, attachment);
        } catch (RuntimeException e) {
            if (attachment != null) {
                attachmentService.release(attachment.getId());
            }
            throw e;
        }
    }

    /**
     * The one place that decides whether a message may be sent, whichever way
     * it arrived (REST or WebSocket): the sender must belong to the chat, a
     * private chat needs someone else in it, and a reply must point at a
     * message from the same chat.
     */
    private void authorizeSend(ChatMessage chatMessage) {
        String chatId = chatMessage.getChatId();
        String senderId = chatMessage.getSenderId();

        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (senderId == null || !chatService.canAccessChat(chatId, senderId)) {
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }

        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> HttpStatusException.notFound("CHAT_NOT_FOUND", "Chat not found"));
        if (chat.getChatType() == ChatType.PRIVATE
                && chat.getParticipantIds().stream().noneMatch(id -> !id.equals(senderId))) {
            throw new IllegalArgumentException("You cannot send messages to yourself");
        }

        String replyTo = chatMessage.getReplyToMessageId();
        if (replyTo != null && !replyTo.isBlank()) {
            Message original = messageRepository.findById(replyTo)
                    .orElseThrow(() -> new IllegalArgumentException("The message you are replying to no longer exists"));
            if (!chatId.equals(original.getChatId())) {
                throw HttpStatusException.forbidden("You can only reply to messages in the same chat");
            }
        }

        chatMessage.setMentionedUserIds(validMentions(chat, senderId, chatMessage.getMentionedUserIds()));
    }

    /** Only people who are in the chat can be tagged, never the sender, at most 20. */
    private List<String> validMentions(Chat chat, String senderId, List<String> requested) {
        if (requested == null) return List.of();
        return requested.stream()
                .filter(id -> id != null && !id.equals(senderId) && chat.getParticipantIds().contains(id))
                .distinct().limit(20).toList();
    }

    private MessageResponse doSend(ChatMessage chatMessage, String authToken, Attachment attachment) {
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
        if (attachment != null) {
            message.setMessageType(attachment.getCategory().toMessageType());
            message.setMediaUrl(null);
            message.setAttachmentId(attachment.getId());
            message.setFileName(attachment.getFileName());
            message.setFileSize(attachment.getSize());
            message.setMimeType(attachment.getMimeType());
        }
        message.setStatus(MessageStatus.SENT);
        message.getReadBy().add(chatMessage.getSenderId());   // never unread for the sender
        message.setCreatedAt(LocalDateTime.now());
        message.setActive(true);
        message.setReplyToMessageId(chatMessage.getReplyToMessageId());
        message.setMentionedUserIds(new ArrayList<>(chatMessage.getMentionedUserIds() == null ? List.of() : chatMessage.getMentionedUserIds()));
        
        // Mark as pending for delivery
        message.setDeliveryState(MessageDeliveryState.PENDING);
        
        // STEP 1: Persist to database first (DURABILITY GUARANTEE)
        Message savedMessage = messageRepository.save(message);
        log.debug("Message persisted to DB: {} | Idempotency Key: {} | Chat: {}", 
            savedMessage.getId(), idempotencyKey, savedMessage.getChatId());

        if (attachment != null) {
            attachmentService.linkMessage(attachment.getId(), savedMessage.getId());
        }

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
                notifySenderOfDeliveryStatus(message);
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
                notifySenderOfDeliveryStatus(message);
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

            notifySenderOfDeliveryStatus(message);

        } catch (Exception e) {
            log.error("Broadcast failed for message {}: {}", message.getId(), e.getMessage(), e);
            message.setDeliveryState(MessageDeliveryState.FAILED_TO_NOTIFY);
            message.setFailureReason(e.getMessage());
            message.setFailedAt(LocalDateTime.now());
            messageRepository.save(message);
            if (metrics != null) {
                metrics.incrementMessageDeliveryFailed();
            }
            notifySenderOfDeliveryStatus(message);
        }
    }

    /**
     * Pushes a live delivery-status update back to the message's own
     * sender over /queue/delivery-status. Without this, a WebSocket
     * client that sends a message has no real-time confirmation it was
     * actually delivered - determineRecipients() deliberately excludes
     * the sender from /queue/messages, so this is the only feedback
     * channel for delivery outcome.
     */
    private void notifySenderOfDeliveryStatus(Message message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("messageId", message.getId());
            payload.put("chatId", message.getChatId());
            payload.put("deliveryState", message.getDeliveryState() != null ? message.getDeliveryState().name() : null);
            payload.put("deliveredAt", message.getDeliveredAt());
            payload.put("failureReason", message.getFailureReason());

            redisMessagePublisher.convertAndSendToUser(message.getSenderId(), "/queue/delivery-status", payload);
        } catch (Exception e) {
            log.warn("Failed to notify sender {} of delivery status for message {}: {}",
                    message.getSenderId(), message.getId(), e.getMessage());
        }
    }

    /**
     * Get messages for a chat with pagination
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getChatMessages(String chatId, String userId, int page, int size, String authToken) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<Message> messages = messageRepository.findByChatIdOrderByCreatedAtDesc(chatId, pageable);

        return messages.stream()
            .filter(Message::isActive)
            .filter(msg -> !msg.getDeletedForUsers().contains(userId))
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

                // Only people in the chat can read its messages, and nobody "reads" their own.
                if (!chatService.canAccessChat(message.getChatId(), userId)) {
                    throw HttpStatusException.forbidden("You are not a member of this chat");
                }
                if (userId.equals(message.getSenderId()) || message.getReadBy().contains(userId)) {
                    return;
                }

                message.getReadBy().add(userId);
                if (message.getStatus() != MessageStatus.READ) {
                    message.setStatus(MessageStatus.READ);
                }
                messageRepository.save(message);
                log.debug("Message {} marked as read by {}", messageId, userId);

                // Notify sender of read receipt
                redisMessagePublisher.convertAndSendToUser(
                    message.getSenderId(),
                    "/queue/read-receipts",
                    Map.of(
                        "messageId", messageId,
                        "chatId", message.getChatId(),
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
     * Marks everything the user has not read in a chat as read (up to 500 at a
     * time) and tells the senders. Returns how many messages were marked.
     */
    public int markChatAsRead(String chatId, String userId) {
        if (!chatService.canAccessChat(chatId, userId)) {
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }
        List<Message> unread = messageRepository.findUnreadFor(chatId, userId, PageRequest.of(0, 500));
        int marked = 0;
        for (Message message : unread) {
            try {
                markMessageAsRead(message.getId(), userId);
                marked++;
            } catch (RuntimeException e) {
                log.warn("Could not mark message {} as read for {}: {}", message.getId(), userId, e.getMessage());
            }
        }
        return marked;
    }

    public long getEditWindowHours() {
        return editWindowHours;
    }

    /** When a message can last be edited, or null if it cannot be edited at all. */
    private LocalDateTime editDeadline(Message message) {
        if (editWindowHours <= 0 || message.isDeletedForEveryone() || message.getCreatedAt() == null) {
            return null;
        }
        return message.getCreatedAt().plusHours(editWindowHours);
    }

    /**
     * Change the text of your own message, within the edit window
     * (message.edit-window-hours). The change goes out to everyone in the chat
     * as an update to the same message id, with editedAt set.
     */
    public MessageResponse editMessage(String messageId, String userId, String newContent,
                                       List<String> mentionedUserIds, String authToken) {
        int attempt = 0;
        while (true) {
            try {
                return doEdit(messageId, userId, newContent, mentionedUserIds, authToken);
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                // A read receipt landed at the same moment. Read the message again and retry.
                if (++attempt >= 3) {
                    throw HttpStatusException.conflict("The message changed while you were editing it. Try again.");
                }
            }
        }
    }

    private MessageResponse doEdit(String messageId, String userId, String newContent,
                                   List<String> mentionedUserIds, String authToken) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getSenderId().equals(userId)) {
            throw HttpStatusException.forbidden("You can only edit your own messages");
        }
        if (!chatService.canAccessChat(message.getChatId(), userId)) {
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }
        if (message.isDeletedForEveryone() || !message.isActive()) {
            throw HttpStatusException.badRequest("A deleted message cannot be edited");
        }
        if (editWindowHours <= 0) {
            throw new HttpStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "EDITING_DISABLED",
                    "Editing messages is turned off");
        }
        LocalDateTime deadline = editDeadline(message);
        if (deadline == null || LocalDateTime.now().isAfter(deadline)) {
            throw new HttpStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "EDIT_WINDOW_EXPIRED",
                    "Messages can only be edited for " + editWindowHours + (editWindowHours == 1 ? " hour" : " hours") + " after they are sent");
        }

        String text = newContent == null ? "" : newContent.trim();
        boolean hasFile = message.getAttachmentId() != null || message.getMediaUrl() != null;
        if (text.isEmpty() && !hasFile) {
            throw HttpStatusException.badRequest("A message cannot be empty");
        }
        if (text.equals(message.getContent())) {
            return convertToMessageResponse(message, authToken);   // nothing changed
        }

        message.setContent(text);
        message.setEditedAt(LocalDateTime.now());
        if (mentionedUserIds != null) {
            Chat chat = chatRepository.findById(message.getChatId()).orElse(null);
            message.setMentionedUserIds(chat == null ? new ArrayList<>()
                    : new ArrayList<>(validMentions(chat, userId, mentionedUserIds)));
        }
        Message saved = messageRepository.save(message);

        MessageResponse response = convertToMessageResponse(saved, authToken);
        broadcastMessage(saved, response);
        log.info("Message {} edited by {}", messageId, userId);
        return response;
    }

    /**
     * Delete message for everyone
     */
    @Transactional
    public void deleteMessageForEveryone(String messageId, String userId, String authToken) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getSenderId().equals(userId)) {
            throw HttpStatusException.forbidden("Only the sender can delete a message for everyone");
        }

        message.setDeletedForEveryone(true);
        message.setContent("This message was deleted");
        message.setEditedAt(LocalDateTime.now());
        message.setActive(false);

        // The file goes with the message, like WhatsApp's "delete for everyone".
        String attachmentId = message.getAttachmentId();
        message.setAttachmentId(null);
        message.setMediaUrl(null);
        messageRepository.save(message);
        attachmentService.deleteForMessage(attachmentId);

        // Notify all participants
        MessageResponse deletedResponse = convertToMessageResponse(message, authToken);
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

        if (!chatService.canAccessChat(message.getChatId(), userId)) {
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }

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
        response.setMediaUrl(resolveMediaUrl(message));
        response.setFileName(message.getFileName());
        response.setFileSize(message.getFileSize());
        response.setMimeType(message.getMimeType());
        response.setAttachmentId(message.getAttachmentId());
        response.setStatus(message.getStatus());
        response.setReadBy(message.getReadBy());
        response.setCreatedAt(message.getCreatedAt());
        response.setEditedAt(message.getEditedAt());
        response.setEditableUntil(editDeadline(message));
        response.setDeleted(message.isDeletedForEveryone());
        response.setReplyToMessageId(message.getReplyToMessageId());
        response.setMentionedUserIds(message.getMentionedUserIds());

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
                preview.setMediaUrl(resolveMediaUrl(original));

                try {
                    UserSummaryResponse sender = userService.getUserSummary(original.getSenderId(), authToken);
                    preview.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
                } catch (Exception ignored) {
                }

                return preview;
            })
            .orElse(null);
    }

    /**
     * Uploaded files get a fresh signed link on every read; older
     * messages that carry a plain mediaUrl (older uploads) are untouched.
     */
    private String resolveMediaUrl(Message message) {
        if (message.getAttachmentId() != null) {
            return attachmentService.signedUrl(message.getAttachmentId());
        }
        return message.getMediaUrl();
    }

    private String truncateForPreview(String content) {
        if (content == null) return "";
        return content.length() > 80 ? content.substring(0, 77) + "..." : content;
    }
}