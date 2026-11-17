package com.premisave.messenger.dto.response;

import lombok.Data;

/** The result of joining a group through an invite link. */
@Data
public class GroupJoinResponse {

    private String groupId;
    private String chatId;
    private String name;
    /** true when the caller was already a member, so nothing changed. */
    private boolean alreadyMember;
}