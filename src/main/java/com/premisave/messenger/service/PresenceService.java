package com.premisave.messenger.service;

import com.premisave.messenger.entity.UserPresence;
import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.repository.UserPresenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String SESSION_COUNT_KEY_PREFIX = "presence:sessions:";

    private final UserPresenceRepository presenceRepository;
    private final RedisMessagePublisher redisMessagePublisher;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Call when a WebSocket session connects. Tracks an active-session
     * count per user in Redis (shared across all instances, so this is
     * correct even if a user's two tabs land on different instances
     * behind a load balancer). Only the FIRST concurrent session
     * actually marks the user online and broadcasts it - a second tab
     * connecting doesn't re-broadcast, since they're already online.
     */
    public void registerConnect(String userId) {
        Long count = stringRedisTemplate.opsForValue().increment(SESSION_COUNT_KEY_PREFIX + userId);
        if (count != null && count == 1L) {
            userOnline(userId);
        } else {
            log.debug("User {} has {} concurrent sessions - already online, no broadcast", userId, count);
        }
    }

    /**
     * Call when a WebSocket session disconnects. Only marks the user
     * offline and broadcasts it once the LAST concurrent session closes
     * (count reaches zero) - closing one of several open tabs no longer
     * incorrectly marks the user offline while they're still connected
     * elsewhere.
     */
    public void registerDisconnect(String userId) {
        Long count = stringRedisTemplate.opsForValue().decrement(SESSION_COUNT_KEY_PREFIX + userId);
        if (count == null || count <= 0L) {
            stringRedisTemplate.delete(SESSION_COUNT_KEY_PREFIX + userId);
            userOffline(userId);
        } else {
            log.debug("User {} still has {} concurrent session(s) - staying online", userId, count);
        }
    }

    private void userOnline(String userId) {
        UserPresence presence = presenceRepository.findById(userId)
                .orElse(new UserPresence());

        presence.setUserId(userId);
        presence.setOnline(true);
        presence.setLastSeen(LocalDateTime.now());
        presenceRepository.save(presence);

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("status", "ONLINE");

        redisMessagePublisher.convertAndSend("/topic/presence", payload);
    }

    private void userOffline(String userId) {
        UserPresence presence = presenceRepository.findById(userId).orElse(null);
        if (presence != null) {
            presence.setOnline(false);
            presence.setLastSeen(LocalDateTime.now());
            presenceRepository.save(presence);

            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId);
            payload.put("status", "OFFLINE");

            redisMessagePublisher.convertAndSend("/topic/presence", payload);
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