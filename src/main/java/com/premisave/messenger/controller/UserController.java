package com.premisave.messenger.controller;

import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.security.CurrentUserResolver;
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
    private final CurrentUserResolver currentUserResolver;

    /** The caller's own profile, with the real user id (the JWT subject is an email). */
    @GetMapping("/me")
    public ResponseEntity<UserSummaryResponse> me(@RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(currentUserResolver.resolve(authHeader));
    }

    /** People lists never include the caller. Pass includeSelf=true to get them anyway. */
    private List<UserSummaryResponse> withoutCaller(List<UserSummaryResponse> users, Authentication authentication, String token) {
        if (users == null) return List.of();
        String email = authentication.getName();
        String id = null;
        try {
            id = currentUserResolver.resolve(token).getId();
        } catch (Exception e) {
            log.debug("Could not resolve the caller id, filtering by email only");
        }
        final String callerId = id;
        return users.stream()
                .filter(u -> u != null
                        && (callerId == null || !callerId.equals(u.getId()))
                        && (email == null || u.getEmail() == null || !email.equalsIgnoreCase(u.getEmail())))
                .toList();
    }

    @GetMapping("/search")
    public ResponseEntity<List<UserSummaryResponse>> searchUsers(
            @RequestParam String query,
            @RequestParam(defaultValue = "false") boolean includeSelf,
            Authentication authentication,
            HttpServletRequest request) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = request.getHeader("Authorization"); // the real JWT
        log.info("SEARCH REQUEST - User: {} | Query: {}", authentication.getName(), query);

        List<UserSummaryResponse> users = userService.searchUsers(query, token);
        return ResponseEntity.ok(includeSelf ? users : withoutCaller(users, authentication, token));
    }

    @GetMapping
    public ResponseEntity<List<UserSummaryResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean includeSelf,
            Authentication authentication,
            HttpServletRequest request) {

        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).build();
        }

        String token = request.getHeader("Authorization"); // the real JWT
        log.info("ALL USERS REQUEST - User: {} | Page: {} Size: {}", authentication.getName(), page, size);

        List<UserSummaryResponse> users = userService.getAllUsers(page, size, token);
        return ResponseEntity.ok(includeSelf ? users : withoutCaller(users, authentication, token));
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