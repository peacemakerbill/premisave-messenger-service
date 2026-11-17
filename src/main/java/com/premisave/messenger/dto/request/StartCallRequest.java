package com.premisave.messenger.dto.request;

import com.premisave.messenger.enums.CallType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StartCallRequest {

    @NotBlank
    private String chatId;

    @NotNull
    private CallType type;
}
