package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.EditMessageRequest;
import com.premisave.messenger.dto.request.SendMessageRequest;
import com.premisave.messenger.dto.response.AttachmentResponse;
import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.AttachmentService;
import com.premisave.messenger.service.ChatService;
import com.premisave.messenger.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Every endpoint resolves the caller through auth-service first. The JWT
 * subject is an email, so Authentication.getName() can never be compared
 * with chat participant ids.
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final AttachmentService attachmentService;
    private final ChatService chatService;
    private final CurrentUserResolver currentUserResolver;

    /**
     * Send text, or text plus a file in one request (multipart).
     * For big files prefer POST /api/attachments, then send the attachmentId.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MessageResponse> sendMultipart(
            @ModelAttribute SendMessageRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(send(request, authHeader));
    }

    /** Send text, or an already uploaded file by attachmentId (JSON). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> sendJson(
            @RequestBody SendMessageRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(send(request, authHeader));
    }

    @PostMapping(value = "/{messageId}/reply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MessageResponse> replyMultipart(
            @PathVariable String messageId,
            @ModelAttribute SendMessageRequest request,
            @RequestHeader("Authorization") String authHeader) {
        request.setReplyToMessageId(messageId);
        return ResponseEntity.ok(send(request, authHeader));
    }

    @PostMapping(value = "/{messageId}/reply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> replyJson(
            @PathVariable String messageId,
            @RequestBody SendMessageRequest request,
            @RequestHeader("Authorization") String authHeader) {
        request.setReplyToMessageId(messageId);
        return ResponseEntity.ok(send(request, authHeader));
    }

    private MessageResponse send(SendMessageRequest request, String authHeader) {
        String senderId = currentUserResolver.resolve(authHeader).getId();

        if (request.getChatId() == null || request.getChatId().isBlank()) {
            throw new IllegalArgumentException("chatId is required");
        }
        if (!chatService.canAccessChat(request.getChatId(), senderId)) {
            log.warn("User {} tried to send to unauthorized chat {}", senderId, request.getChatId());
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }

        // Direct file: store it first, then send exactly like any other attachment.
        String attachmentId = request.getAttachmentId();
        if (request.getFile() != null && !request.getFile().isEmpty()) {
            AttachmentResponse uploaded = attachmentService.upload(request.getFile(), request.getChatId(), senderId);
            attachmentId = uploaded.getAttachmentId();
        }

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(request.getChatId());
        chatMessage.setSenderId(senderId);
        chatMessage.setContent(request.getContent());
        chatMessage.setMessageType(request.getMessageType());
        chatMessage.setAttachmentId(attachmentId);
        chatMessage.setReplyToMessageId(request.getReplyToMessageId());
        chatMessage.setMentionedUserIds(request.getMentionedUserIds());

        MessageResponse response = messageService.sendMessage(chatMessage, authHeader);
        log.info("Message {} sent in chat {} (attachment: {})", response.getId(), request.getChatId(), attachmentId != null);
        return response;
    }

    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader("Authorization") String authHeader) {

        String userId = currentUserResolver.resolve(authHeader).getId();
        if (!chatService.canAccessChat(chatId, userId)) {
            log.warn("User {} tried to read unauthorized chat {}", userId, chatId);
            throw HttpStatusException.forbidden("You are not a member of this chat");
        }
        return ResponseEntity.ok(messageService.getChatMessages(chatId, userId, page, Math.min(size, 100), authHeader));
    }

    @PostMapping("/read/{messageId}")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String messageId,
            @RequestHeader("Authorization") String authHeader) {
        messageService.markMessageAsRead(messageId, currentUserResolver.resolve(authHeader).getId());
        return ResponseEntity.ok().build();
    }

    /** Message settings the client needs, such as how long a sent message can be edited. */
    @GetMapping("/settings")
    public ResponseEntity<java.util.Map<String, Object>> settings() {
        return ResponseEntity.ok(java.util.Map.of("editWindowHours", messageService.getEditWindowHours()));
    }

    /** Edit your own message within the edit window. */
    @PutMapping("/{messageId}")
    public ResponseEntity<MessageResponse> edit(
            @PathVariable String messageId,
            @RequestBody EditMessageRequest request,
            @RequestHeader("Authorization") String authHeader) {
        String userId = currentUserResolver.resolve(authHeader).getId();
        return ResponseEntity.ok(messageService.editMessage(
                messageId, userId, request.getContent(), request.getMentionedUserIds(), authHeader));
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteForEveryone(
            @PathVariable String messageId,
            @RequestHeader("Authorization") String authHeader) {
        messageService.deleteMessageForEveryone(messageId, currentUserResolver.resolve(authHeader).getId(), authHeader);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{messageId}/me")
    public ResponseEntity<Void> deleteForMe(
            @PathVariable String messageId,
            @RequestHeader("Authorization") String authHeader) {
        messageService.deleteMessageForMe(messageId, currentUserResolver.resolve(authHeader).getId());
        return ResponseEntity.noContent().build();
    }
}