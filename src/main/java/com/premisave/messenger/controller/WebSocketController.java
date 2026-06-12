package com.premisave.messenger.controller;

import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, Principal principal) {
        String senderId = principal.getName(); // User email or ID from JWT
        chatMessage.setSenderId(senderId);

        ChatMessage savedMessage = messageService.sendMessage(chatMessage);

        // Send to recipient
        messagingTemplate.convertAndSendToUser(
                chatMessage.getReceiverId(),
                "/queue/messages",
                savedMessage
        );

        // Send confirmation back to sender
        messagingTemplate.convertAndSendToUser(
                senderId,
                "/queue/messages",
                savedMessage
        );
    }

    @MessageMapping("/chat.typing")
    public void userTyping(@Payload ChatMessage typingMessage) {
        messagingTemplate.convertAndSendToUser(
                typingMessage.getReceiverId(),
                "/queue/typing",
                typingMessage
        );
    }
}