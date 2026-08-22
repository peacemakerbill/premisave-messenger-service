package com.premisave.messenger.controller;

import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.service.ChatService;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final MessageService messageService;
    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        if (principal == null || principal.getName() == null) {
            log.warn("Unauthenticated WebSocket message attempt");
            return;
        }

        String senderId = principal.getName();
        chatMessage.setSenderId(senderId);

        // Security: Validate chat access for group messages
        if (chatMessage.getChatId() != null) {
            if (!chatService.canAccessChat(chatMessage.getChatId(), senderId)) {
                log.warn("WebSocket: User {} attempted unauthorized access to chat {}", senderId, chatMessage.getChatId());
                return;
            }
        }

        // Use the REAL JWT stashed by WebSocketAuthInterceptor during CONNECT,
        // not a fabricated "Bearer " + senderId string (senderId is a Mongo
        // ObjectId, not a valid JWT - the old code silently broke every
        // downstream auth-service enrichment call in MessageService).
        String token = resolveRealToken(headerAccessor, senderId);

        try {
            messageService.sendMessage(chatMessage, token);
            log.info("WebSocket message sent by {} in chat {}", senderId, chatMessage.getChatId());
        } catch (Exception e) {
            log.error("Failed to process WebSocket message", e);
        }
    }

    @MessageMapping("/chat.typing")
    public void userTyping(@Payload ChatMessage typingMessage, Principal principal) {
        if (principal == null) return;

        String senderId = principal.getName();
        typingMessage.setSenderId(senderId);

        if (typingMessage.getReceiverId() != null) {
            messagingTemplate.convertAndSendToUser(
                    typingMessage.getReceiverId(),
                    "/queue/typing",
                    typingMessage
            );
        }
    }

    @MessageMapping("/chat.read")
    public void markAsRead(@Payload ChatMessage readMessage, Principal principal) {
        if (principal == null) return;

        String userId = principal.getName();
        if (readMessage.getId() != null) {
            messageService.markMessageAsRead(readMessage.getId(), userId);
        }
    }

    /**
     * Retrieves the real "Bearer <jwt>" token stashed in the STOMP session
     * during CONNECT (see WebSocketAuthInterceptor). Falls back to a
     * clearly-fake token only if it's genuinely missing, so downstream
     * enrichment calls fail gracefully (they already catch exceptions and
     * fall back to "Unknown User") rather than throwing here and dropping
     * the message entirely.
     */
    private String resolveRealToken(SimpMessageHeaderAccessor headerAccessor, String senderId) {
        if (headerAccessor != null && headerAccessor.getSessionAttributes() != null) {
            Object stored = headerAccessor.getSessionAttributes().get("jwt");
            if (stored instanceof String jwt && !jwt.isBlank()) {
                return jwt;
            }
        }
        log.warn("No real JWT found in session for user {} - sender enrichment will fall back to defaults", senderId);
        return "Bearer " + senderId;
    }
}