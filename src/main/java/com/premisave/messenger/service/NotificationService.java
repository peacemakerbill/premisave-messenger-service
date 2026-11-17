package com.premisave.messenger.service;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.request.UserEmailRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sends notifications that leave the messenger: email, and later push.
 *
 * Email goes through auth-service, which owns the mail setup and the addresses. This service only names the
 * user. Set INTERNAL_API_KEY to the same value as API_KEY in auth-service, or no email is sent. A failure here
 * is logged and never reaches the person who caused it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final AuthServiceClient authServiceClient;

    @Value("${auth.service.api-key:}")
    private String apiKey;

    @Value("${notifications.email-enabled:true}")
    private boolean emailEnabled;

    @Async
    public void sendPushNotification(String userId, String title, String body) {
        // TODO: Integrate Firebase / OneSignal later
        log.info("Push notification sent to user {}: {} - {}", userId, title, body);
    }

    @Async
    public void emailUser(String userId, String subject, String title, String message,
                          Map<String, String> details, String buttonLabel, String buttonPath) {
        if (!emailEnabled) return;
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Email to user {} not sent: INTERNAL_API_KEY is not set", userId);
            return;
        }
        try {
            authServiceClient.sendUserEmail(
                    new UserEmailRequest(userId, subject, title, message, details, buttonLabel, buttonPath), apiKey);
        } catch (Exception e) {
            log.warn("Email to user {} could not be requested ({})", userId, e.getClass().getSimpleName());
        }
    }
}
