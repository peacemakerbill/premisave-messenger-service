package com.premisave.messenger.controller;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.request.CreateChatRequest;
import com.premisave.messenger.dto.response.ChatResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
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
    private final AuthServiceClient authServiceClient;

    // ==================== TEMPORARY TEST ENDPOINT ====================
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
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create or Get Private Chat
     * POST /api/chats/private
     */
    @PostMapping("/private")
    public ResponseEntity<ChatResponse> createPrivateChat(
            @RequestBody CreateChatRequest request,
            @RequestHeader("Authorization") String authHeader) {

        UserSummaryResponse currentUser = resolveCurrentUser(authHeader);
        String currentUserId = currentUser.getId();
        String currentUserEmail = currentUser.getEmail();

        String otherUserId = request.getOtherUserId();
        String otherUserEmail = resolveEmail(otherUserId, authHeader);

        String chatId = chatService.getOrCreatePrivateChat(currentUserId, currentUserEmail, otherUserId, otherUserEmail);

        ChatResponse response = new ChatResponse();
        response.setId(chatId);
        response.setChatType(com.premisave.messenger.enums.ChatType.PRIVATE);
        response.setParticipantIds(List.of(currentUserId, otherUserId));

        log.info("Private chat created/retrieved between {} and {}", currentUserId, otherUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all chats for current user
     */
    @GetMapping
    public ResponseEntity<List<ChatResponse>> getMyChats(@RequestHeader("Authorization") String authHeader) {
        String currentUserId = resolveCurrentUser(authHeader).getId();
        List<ChatResponse> chats = chatService.getUserChats(currentUserId);
        return ResponseEntity.ok(chats);
    }

    /**
     * Delete/Archive chat
     */
    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> deleteChat(
            @PathVariable String chatId,
            @RequestHeader("Authorization") String authHeader) {

        String currentUserId = resolveCurrentUser(authHeader).getId();
        chatService.deleteChat(chatId, currentUserId);

        log.info("Chat {} deleted by user {}", chatId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Helper Methods ====================

    /**
     * Resolves the real user profile (real Mongo ID + email) for whoever
     * owns this token, by calling auth-service's /profile/me.
     *
     * IMPORTANT: the JWT's subject claim is the user's EMAIL, not their
     * real ID (confirmed via JwtService.extractUsername -> Claims::getSubject).
     * Never use that value directly as a userId - it must be resolved
     * through auth-service.
     */
    private UserSummaryResponse resolveCurrentUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }
        UserSummaryResponse user = authServiceClient.getCurrentUser(authHeader);
        if (user == null || user.getId() == null) {
            throw new RuntimeException("Unable to resolve current user from token");
        }
        return user;
    }

    /**
     * Resolves the email for a given real userId, used when we already
     * have the ID (e.g. otherUserId from the request body) and just need
     * the email to store alongside it.
     */
    private String resolveEmail(String userId, String authHeader) {
        try {
            UserSummaryResponse summary = authServiceClient.getUserSummary(userId, authHeader);
            return summary != null ? summary.getEmail() : null;
        } catch (Exception e) {
            log.warn("Could not resolve email for userId {}: {}", userId, e.getMessage());
            return null;
        }
    }
}