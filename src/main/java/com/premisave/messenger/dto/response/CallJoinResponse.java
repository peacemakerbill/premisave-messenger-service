package com.premisave.messenger.dto.response;

import lombok.Data;

/** What the app needs to connect: the call, the address of the media server and the person's join token. */
@Data
public class CallJoinResponse {

    private CallResponse call;

    /** The media server address to connect to. Null when the call could not start (for example, the other person was busy). */
    private String url;

    /** The join token. Null together with url. */
    private String token;

    /** The name this person has in the room. Always their user id. */
    private String identity;

    /** HOST, COHOST or MEMBER. */
    private String role;

    /** Set when the call could not start, such as BUSY. */
    private String reason;
}
