package com.premisave.messenger.enums;

/** What a live call event on /user/queue/calls is telling the app. */
public enum CallEventType {
    /** A call is ringing for this person. */
    INCOMING,
    /** The other person's phone is ringing (sent to the caller). */
    RINGING,
    /** Someone was added to the call and their phone is ringing. */
    PARTICIPANT_INVITED,
    PARTICIPANT_JOINED,
    PARTICIPANT_LEFT,
    PARTICIPANT_DECLINED,
    /** Someone did not answer in time. */
    PARTICIPANT_MISSED,
    /** The call is over for everyone. */
    ENDED,
    /** A host removed this person (or someone else) from the call. */
    PARTICIPANT_REMOVED,
    /** A host muted this person (or everyone). */
    MUTED_BY_HOST,
    ROLE_CHANGED,
    LOCK_CHANGED,
    /** A message in the call's chat. */
    CHAT,
    /** A host started recording the call. */
    RECORDING_STARTED,
    /** The recording stopped, or its video file is finished. */
    RECORDING_STOPPED,
    /** The administrators switched recording on or off. */
    RECORDING_SETTING
}
