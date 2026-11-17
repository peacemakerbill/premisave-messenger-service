package com.premisave.messenger.enums;

public enum CallKind {
    /** Started from a private chat. More people can be added during the call. */
    PRIVATE,
    /** Started from a group chat. Any member of the group can join while it is running. */
    GROUP,
    /** Started from a call link. Anyone signed in who has the link can join. */
    LINK
}
