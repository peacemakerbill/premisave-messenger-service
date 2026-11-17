package com.premisave.messenger.enums;

public enum CallStatus {
    /** Started, phones are ringing, nobody else has joined yet. */
    RINGING,
    /** At least two people have been in the call. */
    ACTIVE,
    /** Open but only one person (or nobody) is in it: a group call nobody answered yet, or a call link nobody is using. */
    WAITING,
    ENDED,
    /** Nobody answered before the ring time ran out. */
    MISSED,
    /** A private call that the other person declined. */
    DECLINED,
    /** The caller hung up before anyone answered. */
    CANCELLED;

    /** True while people can still join. */
    public boolean isOpen() {
        return this == RINGING || this == ACTIVE || this == WAITING;
    }
}
