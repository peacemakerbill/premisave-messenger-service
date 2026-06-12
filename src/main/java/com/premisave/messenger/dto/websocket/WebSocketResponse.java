package com.premisave.messenger.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketResponse {

    private String type;        // MESSAGE, READ_RECEIPT, TYPING, PRESENCE, ERROR
    private Object data;
    private String timestamp;
}