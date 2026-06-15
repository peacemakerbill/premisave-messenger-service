package com.premisave.messenger.controller;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.request.SocialActionRequest;
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
@RequestMapping("/api/social")
@RequiredArgsConstructor
public class SocialController {

    private final AuthServiceClient authServiceClient;

    // ── Likes ────────────────────────────────────────────────────────

    @PostMapping("/like")
    public ResponseEntity<SocialActionResponse> likeUser(
            @RequestBody SocialActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("LIKE - User: {} | Target: {}", authentication.getName(), request.getTargetId());
        return ResponseEntity.ok(authServiceClient.likeUser(request, token));
    }

    @DeleteMapping("/unlike/{targetId}")
    public ResponseEntity<SocialActionResponse> unlikeUser(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("UNLIKE - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.unlikeUser(targetId, token));
    }

    // ── Follows ──────────────────────────────────────────────────────

    @PostMapping("/follow")
    public ResponseEntity<SocialActionResponse> followUser(
            @RequestBody SocialActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("FOLLOW - User: {} | Target: {}", authentication.getName(), request.getTargetId());
        return ResponseEntity.ok(authServiceClient.followUser(request, token));
    }

    @DeleteMapping("/unfollow/{targetId}")
    public ResponseEntity<SocialActionResponse> unfollowUser(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("UNFOLLOW - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.unfollowUser(targetId, token));
    }

    // ── Reviews ──────────────────────────────────────────────────────

    @PostMapping("/review")
    public ResponseEntity<SocialActionResponse> reviewUser(
            @RequestBody SocialActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("REVIEW - User: {} | Target: {}", authentication.getName(), request.getTargetId());
        return ResponseEntity.ok(authServiceClient.reviewUser(request, token));
    }

    @PutMapping("/review")
    public ResponseEntity<SocialActionResponse> editReview(
            @RequestBody SocialActionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("EDIT_REVIEW - User: {} | ReviewId: {}", authentication.getName(), request.getReviewId());
        return ResponseEntity.ok(authServiceClient.editReview(request, token));
    }

    @DeleteMapping("/review/{reviewId}")
    public ResponseEntity<SocialActionResponse> deleteReview(
            @PathVariable String reviewId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("DELETE_REVIEW - User: {} | ReviewId: {}", authentication.getName(), reviewId);
        return ResponseEntity.ok(authServiceClient.deleteReview(reviewId, token));
    }

    @GetMapping("/reviews/{targetId}")
    public ResponseEntity<List<ReviewResponse>> getUserReviews(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("GET_REVIEWS - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.getUserReviews(targetId, token));
    }

    @GetMapping("/stats/{userId}")
    public ResponseEntity<UserInteractionResponse> getUserSocialStats(
            @PathVariable String userId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("SOCIAL_STATS - User: {} | Target: {}", authentication.getName(), userId);
        return ResponseEntity.ok(authServiceClient.getUserSocialStats(userId, token));
    }

    @GetMapping("/my-likes")
    public ResponseEntity<List<UserSummaryResponse>> getMyLikes(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_LIKES - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getMyLikes(token));
    }

    @GetMapping("/my-following")
    public ResponseEntity<List<UserSummaryResponse>> getMyFollowing(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_FOLLOWING - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getMyFollowing(token));
    }
}