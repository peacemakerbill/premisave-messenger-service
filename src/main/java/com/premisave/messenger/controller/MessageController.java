package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.MessageResponse;
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
     * Get paginated messages for a specific chat
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

        String userId = authentication.getName();
        log.debug("Fetching messages for chat {} by user {}", chatId, userId);

        List<MessageResponse> messages = messageService.getChatMessages(chatId, page, size);
        return ResponseEntity.ok(messages);
    }

    /**
     * Mark a message as read
     * POST /api/messages/read/{messageId}
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
        
        log.info("Message {} marked as read by user {}", messageId, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * Delete message for everyone
     * DELETE /api/messages/{messageId}
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
        
        log.info("Message {} deleted by user {}", messageId, userId);
        return ResponseEntity.noContent().build();
    }
}