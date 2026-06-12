package com.premisave.messenger.service;

import com.premisave.messenger.entity.UserPresence;
import com.premisave.messenger.repository.UserPresenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PresenceService {

    private final UserPresenceRepository presenceRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public void userOnline(String userId) {
        UserPresence presence = presenceRepository.findById(userId)
                .orElse(new UserPresence());

        presence.setUserId(userId);
        presence.setOnline(true);
        presence.setLastSeen(LocalDateTime.now());
        presenceRepository.save(presence);

        // Broadcast to friends or subscribed users
        messagingTemplate.convertAndSend("/topic/presence", 
            Map.of("userId", userId, "status", "ONLINE"));
    }

    public void userOffline(String userId) {
        UserPresence presence = presenceRepository.findById(userId).orElse(null);
        if (presence != null) {
            presence.setOnline(false);
            presence.setLastSeen(LocalDateTime.now());
            presenceRepository.save(presence);

            messagingTemplate.convertAndSend("/topic/presence", 
                Map.of("userId", userId, "status", "OFFLINE"));
        }
    }

    public UserPresence getPresence(String userId) {
        return presenceRepository.findById(userId)
                .orElseGet(() -> {
                    UserPresence p = new UserPresence();
                    p.setUserId(userId);
                    p.setOnline(false);
                    p.setLastSeen(LocalDateTime.now().minusDays(1));
                    return p;
                });
    }
}