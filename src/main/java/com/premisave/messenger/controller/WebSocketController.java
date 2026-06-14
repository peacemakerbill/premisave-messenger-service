package com.premisave.messenger.controller;

import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, Principal principal) {
        if (principal == null || principal.getName() == null) {
            log.warn("Unauthenticated WebSocket message attempt");
            return;
        }

        String senderId = principal.getName();
        chatMessage.setSenderId(senderId);

        // Token is now handled by WebSocketAuthInterceptor
        String token = "Bearer " + senderId;

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
}