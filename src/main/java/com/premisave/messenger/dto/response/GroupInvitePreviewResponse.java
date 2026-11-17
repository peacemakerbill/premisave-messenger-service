package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * What someone holding an invite link may see before joining: enough to
 * recognise the group, nothing about who is in it.
 */
@Data
public class GroupInvitePreviewResponse {

    private String groupId;
    private String name;
    private String description;
    private String groupPhotoUrl;
    private int memberCount;
    /** true when the caller is already in the group. */
    private boolean alreadyMember;
    /** The group's chat, only filled in when alreadyMember is true. */
    private String chatId;
    private LocalDateTime expiresAt;
}