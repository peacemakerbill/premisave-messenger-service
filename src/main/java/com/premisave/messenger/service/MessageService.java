package com.premisave.messenger.service;

import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.dto.websocket.ChatMessage;
import com.premisave.messenger.entity.Chat;
import com.premisave.messenger.entity.Message;
import com.premisave.messenger.enums.ChatType;
import com.premisave.messenger.enums.MessageStatus;
import com.premisave.messenger.enums.MessageType;
import com.premisave.messenger.exception.MessageNotFoundException;
import com.premisave.messenger.repository.ChatRepository;
import com.premisave.messenger.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final ChatService chatService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    public MessageResponse sendMessage(ChatMessage chatMessage, String authToken) {
        Message message = new Message();
        message.setChatId(chatMessage.getChatId());
        message.setSenderId(chatMessage.getSenderId());
        message.setContent(chatMessage.getContent());
        message.setMessageType(chatMessage.getMessageType() != null ? chatMessage.getMessageType() : MessageType.TEXT);
        message.setMediaUrl(chatMessage.getMediaUrl());
        message.setStatus(MessageStatus.SENT);
        message.setCreatedAt(LocalDateTime.now());
        message.setActive(true);
        message.setReplyToMessageId(chatMessage.getReplyToMessageId());

        Message savedMessage = messageRepository.save(message);
        chatService.updateLastMessage(savedMessage.getChatId(), savedMessage.getId());

        MessageResponse response = convertToMessageResponse(savedMessage, authToken);
        broadcastMessage(savedMessage, response);

        return response;
    }

    private void broadcastMessage(Message message, MessageResponse response) {
        Chat chat = chatRepository.findById(message.getChatId()).orElse(null);
        if (chat == null) {
            messagingTemplate.convertAndSendToUser(message.getSenderId(), "/queue/messages", response);
            return;
        }

        if (chat.getChatType() == ChatType.PRIVATE) {
            chat.getParticipantIds().stream()
                .filter(id -> !id.equals(message.getSenderId()))
                .findFirst()
                .ifPresent(receiver -> messagingTemplate.convertAndSendToUser(receiver, "/queue/messages", response));
            
            messagingTemplate.convertAndSendToUser(message.getSenderId(), "/queue/messages", response);
        } else {
            // Group chat
            chat.getParticipantIds().forEach(memberId ->
                messagingTemplate.convertAndSendToUser(memberId, "/queue/messages", response)
            );
        }
    }

    public List<MessageResponse> getChatMessages(String chatId, int page, int size, String authToken) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        List<Message> messages = messageRepository.findByChatIdOrderByCreatedAtDesc(chatId, pageable);

        return messages.stream()
                .filter(Message::isActive)
                .map(msg -> convertToMessageResponse(msg, authToken))
                .toList();
    }

    public void markMessageAsRead(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getReadBy().contains(userId)) {
            message.getReadBy().add(userId);
            if (message.getStatus() != MessageStatus.READ) {
                message.setStatus(MessageStatus.READ);
            }
            messageRepository.save(message);

            messagingTemplate.convertAndSendToUser(
                    message.getSenderId(),
                    "/queue/read-receipts",
                    Map.of("messageId", messageId, "status", "READ")
            );
        }
    }

    public void deleteMessageForEveryone(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("Only sender can delete for everyone");
        }

        message.setDeletedForEveryone(true);
        message.setContent("This message was deleted");
        message.setEditedAt(LocalDateTime.now());
        message.setActive(false);
        messageRepository.save(message);

        MessageResponse deletedResponse = convertToMessageResponse(message, "Bearer " + userId);
        broadcastMessage(message, deletedResponse);
    }

    public void deleteMessageForMe(String messageId, String userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found: " + messageId));

        if (!message.getDeletedForUsers().contains(userId)) {
            message.getDeletedForUsers().add(userId);
            messageRepository.save(message);
        }
    }

    private MessageResponse convertToMessageResponse(Message message, String authToken) {
        MessageResponse response = new MessageResponse();
        response.setId(message.getId());
        response.setChatId(message.getChatId());
        response.setSenderId(message.getSenderId());
        response.setMessageType(message.getMessageType());
        response.setContent(message.isDeletedForEveryone() ? "This message was deleted" : message.getContent());
        response.setMediaUrl(message.getMediaUrl());
        response.setStatus(message.getStatus());
        response.setReadBy(message.getReadBy());
        response.setCreatedAt(message.getCreatedAt());
        response.setEditedAt(message.getEditedAt());
        response.setDeleted(message.isDeletedForEveryone());
        response.setReplyToMessageId(message.getReplyToMessageId());

        try {
            UserSummaryResponse sender = userService.getUserSummary(message.getSenderId(), authToken);
            response.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
            response.setSenderProfilePic(sender.getProfilePictureUrl());
        } catch (Exception e) {
            response.setSenderName("Unknown User");
        }

        if (message.getReplyToMessageId() != null) {
            response.setReplyPreview(buildReplyPreview(message.getReplyToMessageId(), authToken));
        }

        return response;
    }

    private MessageResponse.ReplyPreview buildReplyPreview(String originalMessageId, String authToken) {
        return messageRepository.findById(originalMessageId)
                .map(original -> {
                    MessageResponse.ReplyPreview preview = new MessageResponse.ReplyPreview();
                    preview.setMessageId(original.getId());
                    preview.setSenderId(original.getSenderId());
                    preview.setContent(truncateForPreview(original.getContent()));
                    preview.setMessageType(original.getMessageType());
                    preview.setMediaUrl(original.getMediaUrl());

                    try {
                        UserSummaryResponse sender = userService.getUserSummary(original.getSenderId(), authToken);
                        preview.setSenderName(sender.getDisplayName() != null ? sender.getDisplayName() : sender.getUsername());
                    } catch (Exception ignored) {}

                    return preview;
                })
                .orElse(null);
    }

    private String truncateForPreview(String content) {
        if (content == null) return "";
        return content.length() > 80 ? content.substring(0, 77) + "..." : content;
    }
}