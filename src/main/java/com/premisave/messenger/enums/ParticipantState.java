package com.premisave.messenger.enums;

public enum ParticipantState {
    /** Their phone is ringing. */
    INVITED,
    /** In the call right now. */
    JOINED,
    /** Was in the call and left, or has not joined yet (a call link's creator before they join). */
    LEFT,
    DECLINED,
    /** Did not answer. */
    MISSED,
    /** Removed by a host. Cannot join again. */
    REMOVED
}
