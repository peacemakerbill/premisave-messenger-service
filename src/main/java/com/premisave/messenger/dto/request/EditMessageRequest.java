package com.premisave.messenger.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class EditMessageRequest {

    // The new text. May be blank only when the message carries a file (a caption).
    private String content;

    // Users tagged with @ in the new text. When null the existing tags are kept.
    private List<String> mentionedUserIds;
    // Whether the new text tags everyone with @all. When null the existing value is kept.
    private Boolean mentionAll;
}