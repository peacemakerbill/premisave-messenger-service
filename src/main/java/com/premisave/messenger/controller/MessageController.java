package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.SendMessageRequest;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for handling all message-related operations.
 * 
 * Supports both direct messaging and reply functionality.
 * All endpoints require valid JWT authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * Send a new message in a chat.
     * 
     * @param request message details (chatId, content, etc.)
     * @param authentication current authenticated user
     * @return created message with full details
     */
    @PostMapping
    public ResponseEntity<MessageResponse> sendMessage(
            @RequestBody SendMessageRequest request,
            Authentication authentication) {

        return sendMessageInternal(request, authentication, false);
    }

    /**
     * Dedicated endpoint to reply to a specific message.
     * 
     * This is the recommended way to create replies as it clearly indicates intent.
     * 
     * Example: POST /api/messages/{originalMessageId}/reply
     * 
     * @param messageId ID of the message being replied to
     * @param request reply content
     * @param authentication current authenticated user
     * @return created reply message
     */
    @PostMapping("/{messageId}/reply")
    public ResponseEntity<MessageResponse> replyToMessage(
            @PathVariable String messageId,
            @RequestBody SendMessageRequest request,
            Authentication authentication) {

        request.setReplyToMessageId(messageId);
        return sendMessageInternal(request, authentication, true);
    }

    /**
     * Internal helper method to handle both normal messages and replies.
     * Reduces code duplication while maintaining clarity.
     */
    private ResponseEntity<MessageResponse> sendMessageInternal(
            SendMessageRequest request,
            Authentication authentication,
            boolean isReply) {

        if (authentication == null || authentication.getName() == null) {
            log.warn("Unauthorized attempt to send message");
            return ResponseEntity.status(401).build();
        }

        String senderId = authentication.getName();

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(request.getChatId());
        chatMessage.setSenderId(senderId);
        chatMessage.setContent(request.getContent());
        chatMessage.setMessageType(request.getMessageType());
        chatMessage.setMediaUrl(request.getMediaUrl());
        chatMessage.setReplyToMessageId(request.getReplyToMessageId());

        ChatMessage savedMessage = messageService.sendMessage(chatMessage);
        MessageResponse response = messageService.convertToMessageResponse(savedMessage);

        if (isReply) {
            log.info("Reply sent by {} to message {} in chat {}", 
                    senderId, request.getReplyToMessageId(), request.getChatId());
        } else {
            log.info("Message sent by {} in chat {}", senderId, request.getChatId());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieve paginated messages from a specific chat.
     * Messages are returned in descending order (newest first).
     */
    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        List<MessageResponse> messages = messageService.getChatMessages(chatId, page, size);
        return ResponseEntity.ok(messages);
    }

    /**
     * Mark a message as read by the current user.
     * Sends real-time read receipt to the sender.
     */
    @PostMapping("/read/{messageId}")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        messageService.markMessageAsRead(messageId, authentication.getName());
        return ResponseEntity.ok().build();
    }

    /**
     * Delete message for everyone (visible to all participants).
     * Only the original sender can perform this action.
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteForEveryone(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        messageService.deleteMessageForEveryone(messageId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Delete message only for the current user (soft delete).
     * Other participants can still see the message.
     */
    @DeleteMapping("/{messageId}/me")
    public ResponseEntity<Void> deleteForMe(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        messageService.deleteMessageForMe(messageId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}