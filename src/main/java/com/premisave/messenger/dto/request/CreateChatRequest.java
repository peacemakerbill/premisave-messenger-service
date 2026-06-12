package com.premisave.messenger.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateChatRequest {

    @NotBlank(message = "Other user ID is required")
    private String otherUserId;
}