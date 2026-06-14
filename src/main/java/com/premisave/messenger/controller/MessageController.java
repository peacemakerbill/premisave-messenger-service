package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.SendMessageRequest;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.enums.MessageType;
import com.premisave.messenger.service.MediaService;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final MediaService mediaService;

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<MessageResponse> sendMessage(
            @ModelAttribute SendMessageRequest request,
            Authentication authentication) {

        return sendMessageInternal(request, authentication, false);
    }

    @PostMapping(value = "/{messageId}/reply", consumes = {"multipart/form-data"})
    public ResponseEntity<MessageResponse> replyToMessage(
            @PathVariable String messageId,
            @ModelAttribute SendMessageRequest request,
            Authentication authentication) {

        request.setReplyToMessageId(messageId);
        return sendMessageInternal(request, authentication, true);
    }

    private ResponseEntity<MessageResponse> sendMessageInternal(
            SendMessageRequest request,
            Authentication authentication,
            boolean isReply) {

        if (authentication == null || authentication.getName() == null) {
            log.warn("Unauthorized attempt");
            return ResponseEntity.status(401).build();
        }

        String senderId = authentication.getName();
        String token = "Bearer " + senderId;

        if (request.getFile() != null && !request.getFile().isEmpty()) {
            try {
                String folder = determineMediaFolder(request.getFile());
                String uploadedUrl = mediaService.uploadMedia(request.getFile(), folder);

                request.setMediaUrl(uploadedUrl);
                request.setFileName(request.getFile().getOriginalFilename());
                request.setFileSize(request.getFile().getSize());

                if (request.getMessageType() == MessageType.TEXT || request.getMessageType() == null) {
                    request.setMessageType(detectMessageType(request.getFile()));
                }
            } catch (Exception e) {
                log.error("File upload failed", e);
                return ResponseEntity.internalServerError().build();
            }
        }

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(request.getChatId());
        chatMessage.setSenderId(senderId);
        chatMessage.setContent(request.getContent());
        chatMessage.setMessageType(request.getMessageType());
        chatMessage.setMediaUrl(request.getMediaUrl());
        chatMessage.setReplyToMessageId(request.getReplyToMessageId());

        MessageResponse response = messageService.sendMessage(chatMessage, token);

        log.info("{} sent successfully in chat {}", isReply ? "Reply" : "Message", request.getChatId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = "Bearer " + authentication.getName();
        List<MessageResponse> messages = messageService.getChatMessages(chatId, page, size, token);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/read/{messageId}")
    public ResponseEntity<Void> markAsRead(@PathVariable String messageId, Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }
        messageService.markMessageAsRead(messageId, authentication.getName());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteForEveryone(@PathVariable String messageId, Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }
        messageService.deleteMessageForEveryone(messageId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{messageId}/me")
    public ResponseEntity<Void> deleteForMe(@PathVariable String messageId, Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }
        messageService.deleteMessageForMe(messageId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    private String determineMediaFolder(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null) {
            if (ct.startsWith("image/")) return "images";
            if (ct.startsWith("video/")) return "videos";
            if (ct.startsWith("audio/")) return "voice";
        }
        return "documents";
    }

    private MessageType detectMessageType(MultipartFile file) {
        String ct = file.getContentType();
        if (ct == null) return MessageType.DOCUMENT;
        if (ct.startsWith("image/")) return MessageType.IMAGE;
        if (ct.startsWith("video/")) return MessageType.VIDEO;
        if (ct.startsWith("audio/")) return MessageType.VOICE;
        return MessageType.DOCUMENT;
    }
}