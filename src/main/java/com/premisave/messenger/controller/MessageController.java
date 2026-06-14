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

/**
 * MessageController - Handles all messaging operations including text, media, and replies.
 * 
 * Key Features:
 * - Send text messages
 * - Send media files (images, videos, documents, voice) with automatic upload to Cloudinary
 * - Reply to messages (with or without media)
 * - Message retrieval, read receipts, and deletion (for everyone / for me)
 * 
 * @author Bill Graham Peacemaker
 * @version 1.1
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final MediaService mediaService;

    /**
     * Send a new message - Supports both text-only and file attachments.
     * 
     * Accepts multipart/form-data to support file uploads.
     * 
     * Example Usage:
     * - Text only: content + chatId
     * - With file: content + chatId + file (image/video/document/voice)
     * 
     * @param request Combined request containing message data and optional file
     * @param authentication Current authenticated user (from JWT)
     */
    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<MessageResponse> sendMessage(
            @ModelAttribute SendMessageRequest request,
            Authentication authentication) {

        return sendMessageInternal(request, authentication, false);
    }

    /**
     * Reply to an existing message.
     * 
     * This endpoint makes reply intent explicit and supports file attachments in replies.
     * 
     * @param messageId ID of the message being replied to
     * @param request Message content + optional file
     * @param authentication Current user
     */
    @PostMapping(value = "/{messageId}/reply", consumes = {"multipart/form-data"})
    public ResponseEntity<MessageResponse> replyToMessage(
            @PathVariable String messageId,
            @ModelAttribute SendMessageRequest request,
            Authentication authentication) {

        request.setReplyToMessageId(messageId);
        return sendMessageInternal(request, authentication, true);
    }

    /**
     * Internal helper method to reduce duplication between sendMessage and replyToMessage.
     * 
     * Handles:
     * 1. Authentication check
     * 2. File upload to Cloudinary (if present)
     * 3. Auto-detection of message type based on file
     * 4. Message processing via MessageService
     * 
     * @param request The message request (may contain file)
     * @param authentication Current authenticated user
     * @param isReply Whether this is a reply operation
     * @return Created message response
     */
    private ResponseEntity<MessageResponse> sendMessageInternal(
            SendMessageRequest request,
            Authentication authentication,
            boolean isReply) {

        if (authentication == null || authentication.getName() == null) {
            log.warn("Unauthorized message attempt");
            return ResponseEntity.status(401).build();
        }

        String senderId = authentication.getName();

        // ====================== FILE UPLOAD HANDLING ======================
        if (request.getFile() != null && !request.getFile().isEmpty()) {
            try {
                // Determine appropriate Cloudinary folder and message type
                String folder = determineMediaFolder(request.getFile());
                String uploadedUrl = mediaService.uploadMedia(request.getFile(), folder);

                // Populate media fields
                request.setMediaUrl(uploadedUrl);
                request.setFileName(request.getFile().getOriginalFilename());
                request.setFileSize(request.getFile().getSize());

                // Auto-detect message type if not explicitly provided
                if (request.getMessageType() == MessageType.TEXT || request.getMessageType() == null) {
                    request.setMessageType(detectMessageType(request.getFile()));
                }

                log.info("File uploaded successfully: {} | Type: {} | URL: {}", 
                        request.getFileName(), request.getMessageType(), uploadedUrl);

            } catch (Exception e) {
                log.error("Media upload failed for chat: {}", request.getChatId(), e);
                return ResponseEntity.internalServerError()
                        .body(null); // Consider custom error response in production
            }
        }

        // ====================== PREPARE AND SEND MESSAGE ======================
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setChatId(request.getChatId());
        chatMessage.setSenderId(senderId);
        chatMessage.setContent(request.getContent());
        chatMessage.setMessageType(request.getMessageType());
        chatMessage.setMediaUrl(request.getMediaUrl());
        chatMessage.setReplyToMessageId(request.getReplyToMessageId());

        // Delegate business logic to service layer
        ChatMessage savedMessage = messageService.sendMessage(chatMessage);
        MessageResponse response = messageService.convertToMessageResponse(savedMessage);

        // Logging for observability
        if (isReply) {
            log.info("Reply sent by {} to message {} in chat {}", 
                    senderId, request.getReplyToMessageId(), request.getChatId());
        } else {
            log.info("Message sent by {} in chat {}", senderId, request.getChatId());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Determines the appropriate Cloudinary folder based on file MIME type.
     */
    private String determineMediaFolder(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            if (contentType.startsWith("image/")) return "images";
            if (contentType.startsWith("video/")) return "videos";
            if (contentType.startsWith("audio/")) return "voice";
        }
        return "documents";
    }

    /**
     * Automatically detects MessageType from file content type.
     * Falls back to DOCUMENT for unknown types.
     */
    private MessageType detectMessageType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) return MessageType.DOCUMENT;

        if (contentType.startsWith("image/")) return MessageType.IMAGE;
        if (contentType.startsWith("video/")) return MessageType.VIDEO;
        if (contentType.startsWith("audio/")) return MessageType.VOICE;

        return MessageType.DOCUMENT;
    }

    // ====================== OTHER MESSAGE OPERATIONS ======================

    /**
     * Retrieve paginated messages from a chat (newest first).
     */
    @GetMapping("/chat/{chatId}")
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable String chatId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        List<MessageResponse> messages = messageService.getChatMessages(chatId, page, size);
        return ResponseEntity.ok(messages);
    }

    /**
     * Mark a message as read and notify sender via WebSocket.
     */
    @PostMapping("/read/{messageId}")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        messageService.markMessageAsRead(messageId, authentication.getName());
        return ResponseEntity.ok().build();
    }

    /**
     * Delete message for all participants (only sender can do this).
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteForEveryone(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        messageService.deleteMessageForEveryone(messageId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    /**
     * Soft delete message only for current user.
     */
    @DeleteMapping("/{messageId}/me")
    public ResponseEntity<Void> deleteForMe(
            @PathVariable String messageId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        messageService.deleteMessageForMe(messageId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}