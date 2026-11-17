package com.premisave.messenger.controller;

import com.premisave.messenger.dto.websocket.CallSignalMessage;
import com.premisave.messenger.service.CallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/** STOMP messages about calls. */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CallWebSocketController {

    private final CallService callService;

    /** The app says its phone is ringing, so the caller's screen can change from "Calling" to "Ringing". */
    @MessageMapping("/call.ringing")
    public void ringing(@Payload CallSignalMessage message, Principal principal) {
        if (principal == null || principal.getName() == null) return;
        if (message == null || message.getCallId() == null || message.getCallId().isBlank()) return;
        try {
            callService.ackRinging(principal.getName(), message.getCallId());
        } catch (RuntimeException e) {
            log.debug("Ringing signal for call {} ignored: {}", message.getCallId(), e.getMessage());
        }
    }
}
