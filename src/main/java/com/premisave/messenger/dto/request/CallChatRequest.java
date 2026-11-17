package com.premisave.messenger.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CallChatRequest {

    @NotBlank
    private String text;
}
