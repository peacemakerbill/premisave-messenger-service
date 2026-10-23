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

import com.premisave.messenger.service.UserService;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Slf4j
@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
public class SocialController {

    private final AuthServiceClient authServiceClient;
    private final UserService userService;

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
        return ResponseEntity.ok(withReviewers(authServiceClient.getUserReviews(targetId, token), token));
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

    // ── Inbound: who liked / follows / reviewed me ────────────────────────────

    @GetMapping("/my-likers")
    public ResponseEntity<List<UserSummaryResponse>> getMyLikers(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_LIKERS - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getMyLikers(token));
    }

    @GetMapping("/my-followers")
    public ResponseEntity<List<UserSummaryResponse>> getMyFollowers(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_FOLLOWERS - User: {}", authentication.getName());
        return ResponseEntity.ok(authServiceClient.getMyFollowers(token));
    }

    @GetMapping("/my-reviews")
    public ResponseEntity<List<ReviewResponse>> getMyReviews(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_REVIEWS - User: {}", authentication.getName());
        return ResponseEntity.ok(withReviewers(authServiceClient.getMyReviews(token), token));
    }

    @GetMapping("/my-written-reviews")
    public ResponseEntity<List<ReviewResponse>> getMyWrittenReviews(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MY_WRITTEN_REVIEWS - User: {}", authentication.getName());
        return ResponseEntity.ok(withReviewers(authServiceClient.getMyWrittenReviews(token), token));
    }

    // ── Reviewer lookup ──────────────────────────────────────────────────────

    /**
     * Attaches the reviewer's profile to each review. The review stores whatever
     * the auth-service saved for the reviewer, which can be an email rather than
     * an id, so a failed id lookup falls back to a search on that value.
     */
    private List<ReviewResponse> withReviewers(List<ReviewResponse> reviews, String token) {
        if (reviews == null) return List.of();
        Map<String, UserSummaryResponse> found = new HashMap<>();
        for (ReviewResponse review : reviews) {
            String ref = review.getUserId();
            if (ref == null || ref.isBlank()) continue;
            if (!found.containsKey(ref)) found.put(ref, resolveUser(ref, token));
            review.setReviewer(found.get(ref));
        }
        return reviews;
    }

    private UserSummaryResponse resolveUser(String ref, String token) {
        try {
            UserSummaryResponse user = userService.getUserSummary(ref, token);
            boolean fallback = user == null || "Unknown User".equals(user.getDisplayName());
            if (!fallback) return user;
            for (UserSummaryResponse candidate : userService.searchUsers(ref, token)) {
                if (candidate != null && (ref.equalsIgnoreCase(candidate.getId())
                        || ref.equalsIgnoreCase(candidate.getEmail())
                        || ref.equalsIgnoreCase(candidate.getUsername()))) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve reviewer {}: {}", ref, e.getMessage());
        }
        return null;
    }

    // ── Relationship status checks ────────────────────────────────────────────

    @GetMapping("/like/status/{targetId}")
    public ResponseEntity<SocialStatusResponse> getLikeStatus(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("LIKE_STATUS - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.getLikeStatus(targetId, token));
    }

    @GetMapping("/follow/status/{targetId}")
    public ResponseEntity<SocialStatusResponse> getFollowStatus(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("FOLLOW_STATUS - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.getFollowStatus(targetId, token));
    }

    @GetMapping("/review/status/{targetId}")
    public ResponseEntity<SocialStatusResponse> getReviewStatus(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("REVIEW_STATUS - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.getReviewStatus(targetId, token));
    }

    @GetMapping("/follow/mutual/{targetId}")
    public ResponseEntity<SocialStatusResponse> getMutualFollowStatus(
            @PathVariable String targetId,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String token = httpRequest.getHeader("Authorization");
        log.info("MUTUAL_FOLLOW - User: {} | Target: {}", authentication.getName(), targetId);
        return ResponseEntity.ok(authServiceClient.getMutualFollowStatus(targetId, token));
    }
}