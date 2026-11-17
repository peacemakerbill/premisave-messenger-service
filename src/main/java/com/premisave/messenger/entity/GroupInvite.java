package com.premisave.messenger.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * An invite link for a group. The code is the secret part of the link: anyone
 * who holds it can join until it expires, runs out of uses, or an admin resets
 * or revokes it. A group has at most one working link at a time.
 */
@Data
@Document(collection = "group_invites")
@CompoundIndex(name = "group_invites_group_idx", def = "{'groupId': 1, 'revoked': 1}")
public class GroupInvite {

    @Id
    private String id;

    @Indexed(unique = true)
    private String code;

    private String groupId;
    private String createdBy;
    private LocalDateTime createdAt;

    /** null means the link never expires. */
    private LocalDateTime expiresAt;

    /** null means unlimited joins. */
    private Integer maxUses;

    /** How many people joined through this link. */
    private int uses;

    private boolean revoked;
    private LocalDateTime revokedAt;
}