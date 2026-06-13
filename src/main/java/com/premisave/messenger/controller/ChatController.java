package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.CreateChatRequest;
import com.premisave.messenger.dto.response.ChatResponse;
import com.premisave.messenger.security.JwtService;
import com.premisave.messenger.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final JwtService jwtService;

    // ==================== TEST ENDPOINT (Temporary) ====================
    @GetMapping("/test-token")
    public ResponseEntity<?> testToken(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            boolean valid = jwtService.isTokenValid(token);
            String username = jwtService.extractUsername(token);

            return ResponseEntity.ok(Map.of(
                "valid", valid,
                "username", username != null ? username : "null",
                "tokenStartsWith", token.substring(0, 30) + "..."
            ));
        } catch (Exception e) {
            log.error("Token test failed", e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "status", "failed"
            ));
        }
    }

    /**
     * Create or get private chat between current user and another user
     */
    @PostMapping("/private")
    public ResponseEntity<ChatResponse> createPrivateChat(
            @RequestBody CreateChatRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String currentUserId = extractUserIdFromToken(authHeader);
        String chatId = chatService.getOrCreatePrivateChat(currentUserId, request.getOtherUserId());

        // You can enhance this to return full chat details
        ChatResponse response = new ChatResponse();
        response.setId(chatId);
        response.setChatType(com.premisave.messenger.enums.ChatType.PRIVATE);
        response.setParticipantIds(List.of(currentUserId, request.getOtherUserId()));

        return ResponseEntity.ok(response);
    }

    /**
     * Get all chats for current user
     */
    @GetMapping
    public ResponseEntity<List<ChatResponse>> getUserChats(@RequestHeader("Authorization") String authHeader) {
        String currentUserId = extractUserIdFromToken(authHeader);
        List<ChatResponse> chats = chatService.getUserChats(currentUserId);
        return ResponseEntity.ok(chats);
    }

    /**
     * Delete / Archive chat
     */
    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> deleteChat(
            @PathVariable String chatId,
            @RequestHeader("Authorization") String authHeader) {

        String currentUserId = extractUserIdFromToken(authHeader);
        chatService.deleteChat(chatId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    // Helper method to extract user ID from token
    private String extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Invalid authorization header");
        }
        String token = authHeader.substring(7);
        String userId = jwtService.extractUsername(token);
        if (userId == null) {
            throw new RuntimeException("Invalid or expired token");
        }
        return userId;
    }
}