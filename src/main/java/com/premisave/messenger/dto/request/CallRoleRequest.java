package com.premisave.messenger.dto.request;

import com.premisave.messenger.enums.CallRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CallRoleRequest {

    /** COHOST or MEMBER. HOST hands the host role over: the person who asked becomes a co-host. */
    @NotNull
    private CallRole role;
}
