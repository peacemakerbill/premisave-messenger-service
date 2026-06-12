package com.premisave.messenger.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    @Async
    public void sendPushNotification(String userId, String title, String body) {
        // TODO: Integrate Firebase / OneSignal later
        log.info("Push notification sent to user {}: {} - {}", userId, title, body);
    }

    @Async
    public void sendEmailNotification(String email, String subject, String content) {
        log.info("Email notification sent to {}: {}", email, subject);
    }
}