package com.premisave.messenger.enums;

/** What happened to the person a notification is for. */
public enum NotificationType {
    MADE_ADMIN,
    REMOVED_ADMIN,
    BECAME_OWNER,
    ADDED_TO_GROUP,
    REMOVED_FROM_GROUP,
    /** Someone tagged this person with @ in a chat. */
    MENTIONED,
    /** Someone tagged everyone with @all in a group. */
    MENTIONED_ALL
}
