package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.NotificationResponse;
import com.premisave.messenger.security.CurrentUserResolver;
import com.premisave.messenger.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The signed-in person's notifications: made an admin or the owner, added to or removed from a group.
 * New ones also arrive live on /user/queue/notifications. Every answer is JSON.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final UserNotificationService notificationService;
    private final CurrentUserResolver currentUserResolver;

    private String me(String authHeader) {
        return currentUserResolver.resolve(authHeader).getId();
    }

    /** The newest first. limit is 1 to 100, default 50. */
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader) {

        return ResponseEntity.ok(notificationService.list(me(authHeader), limit));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(Map.of("count", notificationService.unreadCount(me(authHeader))));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Boolean>> markRead(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        notificationService.markRead(me(authHeader), id);
        return ResponseEntity.ok(Map.of("read", true));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Long>> markAllRead(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(Map.of("updated", notificationService.markAllRead(me(authHeader))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> delete(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        notificationService.delete(me(authHeader), id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /** Removes all of the person's notifications. */
    @DeleteMapping
    public ResponseEntity<Map<String, Long>> clear(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(Map.of("deleted", notificationService.clear(me(authHeader))));
    }
}
