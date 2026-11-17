package com.premisave.messenger.controller;

import com.premisave.messenger.service.CallRecordingService;
import com.premisave.messenger.service.CallService;
import com.premisave.messenger.service.LiveKitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * LiveKit tells us when someone connects to or drops off a room. That is how a call notices a crashed app or a lost
 * connection straight away. Without it the call still works: a check every 20 seconds finds the same people.
 *
 * The request is open to the network but carries a header signed with the LiveKit secret, which is checked here.
 */
@Slf4j
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallWebhookController {

    private final CallService callService;
    private final CallRecordingService recordingService;
    private final LiveKitService liveKit;
    private final ObjectMapper objectMapper;

    @PostMapping("/livekit-webhook")
    public ResponseEntity<Void> receive(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (!liveKit.verifyWebhook(authorization, body)) {
            return ResponseEntity.status(401).build();
        }
        try {
            Map<?, ?> event = objectMapper.readValue(body, Map.class);
            String name = text(event.get("event"));
            // Recorder reports have no participant, so they are handled before the check below.
            if (name != null && name.startsWith("egress_")) {
                if (event.get("egressInfo") instanceof Map<?, ?> info) recordingService.onEgressEvent(info);
                return ResponseEntity.ok().build();
            }
            String room = text(nested(event, "room", "name"));
            String identity = text(nested(event, "participant", "identity"));
            if (name == null || room == null || identity == null) return ResponseEntity.ok().build();

            switch (name) {
                case "participant_joined" -> callService.onLiveKitJoined(room, identity);
                case "participant_left" -> callService.onLiveKitLeft(room, identity,
                        number(event.get("createdAt")), text(nested(event, "participant", "disconnectReason")));
                default -> log.debug("LiveKit event {} ignored", name);
            }
        } catch (Exception e) {
            // Always answer 200 for a well-signed request, or LiveKit keeps retrying the same event.
            log.warn("Could not process a LiveKit webhook: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private static Object nested(Map<?, ?> map, String key, String inner) {
        Object o = map.get(key);
        return o instanceof Map<?, ?> m ? m.get(inner) : null;
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** LiveKit sends 64-bit numbers as text. */
    private static long number(Object o) {
        if (o == null) return 0;
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
