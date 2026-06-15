package com.premisave.messenger.controller;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileViewController {

    private final AuthServiceClient authServiceClient;

    @GetMapping("/me")
    public ResponseEntity<UserSummaryResponse> getMyProfile(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("GET_MY_PROFILE - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getCurrentUser(token));
    }

    @PostMapping("/views/{targetId}")
    public ResponseEntity<ProfileViewResponse> recordView(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("RECORD_VIEW - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.recordProfileView(targetId, token));
    }

    @GetMapping("/views/who-viewed-me")
    public ResponseEntity<List<ProfileViewResponse>> getWhoViewedMe(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("WHO_VIEWED_ME - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getWhoViewedMe(token));
    }

    @GetMapping("/views/who-i-viewed")
    public ResponseEntity<List<WhoIViewedResponse>> getWhoIViewed(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("WHO_I_VIEWED - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getWhoIViewed(token));
    }

    @GetMapping("/views/my-stats")
    public ResponseEntity<ProfileViewStats> getMyStats(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_VIEW_STATS - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getMyProfileViewStats(token));
    }

    @GetMapping("/views/stats")
    public ResponseEntity<?> getStats(
            @RequestParam(required = false) String userId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("VIEW_STATS - User: {} | UserId param: {}", authentication.getName(), userId);
        return ResponseEntity.ok(authServiceClient.getProfileViewStats(userId, token));
    }

    @GetMapping("/views/stats/{userId}")
    public ResponseEntity<PublicProfileViewStats> getUserStats(
            @PathVariable String userId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("USER_VIEW_STATS - User: {} | Target: {}", authentication.getName(), userId);
        return ResponseEntity.ok(authServiceClient.getUserProfileViewStats(userId, token));
    }
}