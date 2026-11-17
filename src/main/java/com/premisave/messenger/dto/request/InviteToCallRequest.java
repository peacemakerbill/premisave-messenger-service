package com.premisave.messenger.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class InviteToCallRequest {

    @NotEmpty
    private List<String> userIds;
}
