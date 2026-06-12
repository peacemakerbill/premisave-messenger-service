package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {

        List<MessageResponse> messages = messageService.getChatMessages(chatId, page, size);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/read/{messageId}")
    public ResponseEntity<Void> markAsRead(@PathVariable String messageId, Authentication authentication) {
        String userId = authentication.getName();
        messageService.markMessageAsRead(messageId, userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable String messageId, Authentication authentication) {
        String userId = authentication.getName();
        messageService.deleteMessageForEveryone(messageId, userId);
        return ResponseEntity.ok().build();
    }
}