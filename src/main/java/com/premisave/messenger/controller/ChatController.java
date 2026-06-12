package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.ChatResponse;
import com.premisave.messenger.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/private")
    public ResponseEntity<String> createPrivateChat(@RequestParam String otherUserId,
                                                    Authentication authentication) {
        String currentUserId = authentication.getName();
        String chatId = chatService.getOrCreatePrivateChat(currentUserId, otherUserId);
        return ResponseEntity.ok(chatId);
    }

    @GetMapping
    public ResponseEntity<List<ChatResponse>> getMyChats(Authentication authentication) {
        String userId = authentication.getName();
        List<ChatResponse> chats = chatService.getUserChats(userId);
        return ResponseEntity.ok(chats);
    }

    @DeleteMapping("/{chatId}")
    public ResponseEntity<String> deleteChat(@PathVariable String chatId, Authentication authentication) {
        String userId = authentication.getName();
        chatService.deleteChat(chatId, userId);
        return ResponseEntity.ok("Chat deleted successfully");
    }
}