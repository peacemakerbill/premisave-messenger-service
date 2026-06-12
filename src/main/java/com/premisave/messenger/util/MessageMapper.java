package com.premisave.messenger.util;

import com.premisave.messenger.dto.response.MessageResponse;
import com.premisave.messenger.entity.Message;
import org.springframework.stereotype.Component;

@Component
public class MessageMapper {

    public MessageResponse toResponse(Message message) {
        MessageResponse response = new MessageResponse();
        response.setId(message.getId());
        response.setChatId(message.getChatId());
        response.setSenderId(message.getSenderId());
        response.setMessageType(message.getMessageType());
        response.setContent(message.getContent());
        response.setMediaUrl(message.getMediaUrl());
        response.setStatus(message.getStatus());
        response.setCreatedAt(message.getCreatedAt());
        response.setDeleted(message.isDeletedForEveryone());
        return response;
    }
}