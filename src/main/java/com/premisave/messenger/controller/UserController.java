package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * Search users by name, username or email
     */
    @GetMapping("/search")
    public ResponseEntity<List<UserSummaryResponse>> searchUsers(
            @RequestParam String query,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = "Bearer " + authentication.getName();
        log.info("SEARCH REQUEST - User: {} | Query: {}", authentication.getName(), query);

        List<UserSummaryResponse> users = userService.searchUsers(query, token);
        return ResponseEntity.ok(users);
    }

    /**
     * Get all users (paginated)
     */
    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = "Bearer " + authentication.getName();
        log.info("ALL USERS REQUEST - User: {} | Page: {} Size: {}", authentication.getName(), page, size);

        List<UserSummaryResponse> users = userService.getAllUsers(page, size, token);
        return ResponseEntity.ok(users);
    }

    /**
     * Get other user's full profile
     */
    @GetMapping("/{userId}")
    public ResponseEntity<UserSummaryResponse> getUserProfile(
            @PathVariable String userId,
            Authentication authentication) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = "Bearer " + authentication.getName();
        log.info("PROFILE REQUEST - User: {} | Target: {}", authentication.getName(), userId);

        UserSummaryResponse user = userService.getUserSummary(userId, token);
        return ResponseEntity.ok(user);
    }
}