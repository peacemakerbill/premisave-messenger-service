package com.premisave.messenger.controller;

import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.security.WebSocketPrincipal;
import com.premisave.messenger.service.ChatService;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final MessageService messageService;
    private final ChatService chatService;
    private final RedisMessagePublisher redisMessagePublisher;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, Principal principal) {
        if (principal == null || principal.getName() == null) {
            log.warn("Unauthenticated WebSocket message attempt");
            return;
        }

        String senderId = principal.getName();
        chatMessage.setSenderId(senderId);

        // Access, self-messaging and reply checks happen in MessageService.sendMessage,
        // the single place every message goes through.

        // Use the REAL JWT carried by WebSocketPrincipal (set during CONNECT
        // by WebSocketAuthInterceptor), not a fabricated "Bearer " + senderId
        // string - senderId is a Mongo ObjectId, not a valid JWT, so that
        // fake token silently broke every downstream auth-service enrichment
        // call in MessageService.
        String token = resolveRealToken(principal, senderId);

        try {
            messageService.sendMessage(chatMessage, token);
            log.info("WebSocket message sent by {} in chat {}", senderId, chatMessage.getChatId());
        } catch (HttpStatusException e) {
            log.warn("WebSocket message refused for {}: {}", senderId, e.getMessage());
            notifySendFailure(senderId, chatMessage.getChatId(), e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            // Bad request from the client (empty message, attachment already
            // sent or not theirs). Tell the sender instead of dropping it.
            log.warn("WebSocket message rejected for {}: {}", senderId, e.getMessage());
            notifySendFailure(senderId, chatMessage.getChatId(), "INVALID_REQUEST", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to process WebSocket message", e);
            notifySendFailure(senderId, chatMessage.getChatId(), "SEND_FAILED", "The message could not be sent");
        }
    }

    private void notifySendFailure(String userId, String chatId, String code, String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", code);
            payload.put("message", message);
            payload.put("chatId", chatId);
            redisMessagePublisher.convertAndSendToUser(userId, "/queue/errors", payload);
        } catch (Exception e) {
            log.warn("Could not report send failure to {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Typing is relayed only inside a private chat the sender belongs to, and
     * only to the other person in it. The receiver is worked out here, never
     * taken from the client, so nobody can ping a stranger.
     */
    @MessageMapping("/chat.typing")
    public void userTyping(@Payload ChatMessage typingMessage, Principal principal) {
        if (principal == null || principal.getName() == null) return;

        String senderId = principal.getName();
        String chatId = typingMessage.getChatId();
        if (chatId == null || chatId.isBlank()) return;

        chatService.otherParticipant(chatId, senderId).ifPresent(receiverId -> {
            ChatMessage relay = new ChatMessage();
            relay.setChatId(chatId);
            relay.setSenderId(senderId);
            relay.setReceiverId(receiverId);
            redisMessagePublisher.convertAndSendToUser(receiverId, "/queue/typing", relay);
        });
    }

    @MessageMapping("/chat.read")
    public void markAsRead(@Payload ChatMessage readMessage, Principal principal) {
        if (principal == null || principal.getName() == null) return;

        String userId = principal.getName();
        if (readMessage.getId() == null) return;
        try {
            messageService.markMessageAsRead(readMessage.getId(), userId);
        } catch (RuntimeException e) {
            log.warn("WebSocket read refused for {} on message {}: {}", userId, readMessage.getId(), e.getMessage());
        }
    }

    /**
     * Retrieves the real "Bearer <jwt>" token carried by WebSocketPrincipal.
     * The Principal injected here is the UsernamePasswordAuthenticationToken
     * set in WebSocketAuthInterceptor during CONNECT; its wrapped principal
     * is a WebSocketPrincipal holding both the real userId (returned by
     * getName(), already proven reliable via senderId above) and the token.
     *
     * Falls back to a clearly-fake token only if it's genuinely missing
     * (e.g. connected before this fix was deployed), so downstream
     * enrichment calls fail gracefully (they already catch exceptions and
     * fall back to "Unknown User") rather than throwing here and dropping
     * the message entirely.
     */
    private String resolveRealToken(Principal principal, String senderId) {
        if (principal instanceof UsernamePasswordAuthenticationToken authToken
                && authToken.getPrincipal() instanceof WebSocketPrincipal wsPrincipal) {
            String token = wsPrincipal.getToken();
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        log.warn("No real JWT found on principal for user {} - sender enrichment will fall back to defaults", senderId);
        return "Bearer " + senderId;
    }
}