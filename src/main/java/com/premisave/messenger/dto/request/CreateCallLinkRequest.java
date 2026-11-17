package com.premisave.messenger.dto.request;

import com.premisave.messenger.enums.CallType;
import lombok.Data;

@Data
public class CreateCallLinkRequest {

    /** Defaults to VIDEO. */
    private CallType type;

    /** A name shown to people who open the link. Optional. */
    private String title;

    /** How long the link works, in hours. Defaults to the longest allowed. */
    private Integer expiresInHours;
}
