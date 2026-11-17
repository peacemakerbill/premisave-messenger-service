package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/** A group's invite link. link is what people share and what the QR code holds. */
@Data
public class GroupInviteResponse {

    private String groupId;
    private String code;
    private String link;
    private String createdBy;
    private LocalDateTime createdAt;
    /** null means it never expires. */
    private LocalDateTime expiresAt;
    /** null means unlimited. */
    private Integer maxUses;
    private int uses;
}