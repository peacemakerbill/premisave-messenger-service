package com.premisave.messenger.controller;

import com.premisave.messenger.dto.request.CallSettingsRequest;
import com.premisave.messenger.dto.response.CallSettingsResponse;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.CallRecordingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The call switches the administrators control from the dashboard. Everyone can read them (the app hides recording when it
 * is off), only an administrator can change them.
 */
@RestController
@RequestMapping("/api/call-settings")
@RequiredArgsConstructor
public class CallSettingsController {

    private final CallRecordingService recordingService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ResponseEntity<CallSettingsResponse> get(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(recordingService.settingsFor(currentUserResolver.resolve(authHeader)));
    }

    /** Administrators only: turns call recording on or off for everyone. */
    @PutMapping("/recording")
    public ResponseEntity<CallSettingsResponse> setRecording(
            @RequestBody CallSettingsRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(recordingService.setEnabled(currentUserResolver.resolve(authHeader), request.isEnabled()));
    }
}
