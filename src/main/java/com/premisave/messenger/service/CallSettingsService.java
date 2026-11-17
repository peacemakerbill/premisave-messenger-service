package com.premisave.messenger.service;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.entity.CallSettings;
import com.premisave.messenger.repository.CallSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * The switches the administrators control for calls. Until an administrator changes one, the value from
 * application.yml (calls.recording-enabled) applies. The value is cached for a few seconds, because every call
 * response asks for it.
 */
@Service
@RequiredArgsConstructor
public class CallSettingsService {

    private static final long CACHE_MILLIS = 5_000;

    private final CallSettingsRepository repository;
    private final CallProperties props;

    private volatile boolean cachedEnabled;
    private volatile long cachedAt;

    /** The administrators' switch for recording. */
    public boolean recordingSwitchedOn() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0 && now - cachedAt < CACHE_MILLIS) return cachedEnabled;
        boolean value = repository.findById(CallSettings.ID)
                .map(CallSettings::isRecordingEnabled)
                .orElse(props.isRecordingEnabled());
        cachedEnabled = value;
        cachedAt = now;
        return value;
    }

    /** Whether recording can be used right now: the switch is on and calls are set up. */
    public boolean recordingAvailable() {
        return props.isConfigured() && recordingSwitchedOn();
    }

    public boolean setRecordingEnabled(boolean enabled, String adminId) {
        CallSettings settings = repository.findById(CallSettings.ID).orElseGet(CallSettings::new);
        settings.setRecordingEnabled(enabled);
        settings.setUpdatedBy(adminId);
        settings.setUpdatedAt(LocalDateTime.now());
        repository.save(settings);
        cachedEnabled = enabled;
        cachedAt = System.currentTimeMillis();
        return enabled;
    }
}
