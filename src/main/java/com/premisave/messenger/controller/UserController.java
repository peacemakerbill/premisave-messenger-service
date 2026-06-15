package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
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

    @GetMapping("/search")
    public ResponseEntity<List<UserSummaryResponse>> searchUsers(
            @RequestParam String query,
            Authentication authentication,
            HttpServletRequest request) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = request.getHeader("Authorization"); // the real JWT
        log.info("SEARCH REQUEST - User: {} | Query: {}", authentication.getName(), query);

        List<UserSummaryResponse> users = userService.searchUsers(query, token);
        return ResponseEntity.ok(users);
    }

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication,
            HttpServletRequest request) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = request.getHeader("Authorization"); // the real JWT
        log.info("ALL USERS REQUEST - User: {} | Page: {} Size: {}", authentication.getName(), page, size);

        List<UserSummaryResponse> users = userService.getAllUsers(page, size, token);
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserSummaryResponse> getUserProfile(
            @PathVariable String userId,
            Authentication authentication,
            HttpServletRequest request) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = request.getHeader("Authorization"); // the real JWT
        log.info("PROFILE REQUEST - User: {} | Target: {}", authentication.getName(), userId);

        UserSummaryResponse user = userService.getUserSummary(userId, token);
        return ResponseEntity.ok(user);
    }
}