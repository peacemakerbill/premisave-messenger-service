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

@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * Send a new message via REST API
     * POST /api/messages
     */
    @PostMapping
    public ResponseEntity<MessageResponse> sendMessage(
            @RequestBody SendMessageRequest request,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String senderId = authentication.getName();

        // Prepare WebSocket-compatible message
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(request.getChatId());
        chatMessage.setSenderId(senderId);
        chatMessage.setReceiverId(null); // Will be handled in service if needed
        chatMessage.setContent(request.getContent());
        chatMessage.setMessageType(request.getMessageType());
        chatMessage.setMediaUrl(request.getMediaUrl());

        ChatMessage saved = messageService.sendMessage(chatMessage);

        MessageResponse response = messageService.convertToMessageResponse(saved);

        log.info("Message sent via REST from {} in chat {}", senderId, request.getChatId());
        return ResponseEntity.ok(response);
    }

    /**
     * Get paginated messages for a chat
     * GET /api/messages/chat/{chatId}?page=0&size=50
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
     * Mark message as read
     */
    @PostMapping("/read/{messageId}")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = authentication.getName();
        messageService.markMessageAsRead(messageId, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * Delete message for everyone
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = authentication.getName();
        messageService.deleteMessageForEveryone(messageId, userId);
        return ResponseEntity.noContent().build();
    }
}