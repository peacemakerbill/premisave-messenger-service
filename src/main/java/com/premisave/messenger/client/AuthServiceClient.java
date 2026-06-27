package com.premisave.messenger.client;

import com.premisave.messenger.dto.request.SocialActionRequest;
import com.premisave.messenger.dto.response.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(name = "auth-service", url = "${auth.service.url:http://localhost:8080}")
public interface AuthServiceClient {

    // ── User / Profile ──────────────────────────────────────────────

    @GetMapping("/profile/user/{userId}")
    UserSummaryResponse getUserSummary(
            @PathVariable String userId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/me")
    UserSummaryResponse getCurrentUser(
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/search")
    List<UserSummaryResponse> searchUsers(
            @RequestParam("query") String query,
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/all")
    List<UserSummaryResponse> getAllUsers(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestHeader("Authorization") String token);

    // ── Social ──────────────────────────────────────────────────────

    @PostMapping("/social/like")
    SocialActionResponse likeUser(
            @RequestBody SocialActionRequest request,
            @RequestHeader("Authorization") String token);

    @DeleteMapping("/social/unlike/{targetId}")
    SocialActionResponse unlikeUser(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @PostMapping("/social/follow")
    SocialActionResponse followUser(
            @RequestBody SocialActionRequest request,
            @RequestHeader("Authorization") String token);

    @DeleteMapping("/social/unfollow/{targetId}")
    SocialActionResponse unfollowUser(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @PostMapping("/social/review")
    SocialActionResponse reviewUser(
            @RequestBody SocialActionRequest request,
            @RequestHeader("Authorization") String token);

    @PutMapping("/social/review")
    SocialActionResponse editReview(
            @RequestBody SocialActionRequest request,
            @RequestHeader("Authorization") String token);

    @DeleteMapping("/social/review/{reviewId}")
    SocialActionResponse deleteReview(
            @PathVariable String reviewId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/reviews/{targetId}")
    List<ReviewResponse> getUserReviews(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/stats/{userId}")
    UserInteractionResponse getUserSocialStats(
            @PathVariable String userId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/my-likes")
    List<UserSummaryResponse> getMyLikes(
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/my-following")
    List<UserSummaryResponse> getMyFollowing(
            @RequestHeader("Authorization") String token);

    // ── Inbound: who liked / follows / reviewed me ────────────────────────────

    @GetMapping("/social/my-likers")
    List<UserSummaryResponse> getMyLikers(
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/my-followers")
    List<UserSummaryResponse> getMyFollowers(
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/my-reviews")
    List<ReviewResponse> getMyReviews(
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/my-written-reviews")
    List<ReviewResponse> getMyWrittenReviews(
            @RequestHeader("Authorization") String token);

    // ── Relationship status checks ────────────────────────────────────────────

    @GetMapping("/social/like/status/{targetId}")
    SocialStatusResponse getLikeStatus(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/follow/status/{targetId}")
    SocialStatusResponse getFollowStatus(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/review/status/{targetId}")
    SocialStatusResponse getReviewStatus(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/social/follow/mutual/{targetId}")
    SocialStatusResponse getMutualFollowStatus(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    // ── Profile Views ────────────────────────────────────────────────

    @PostMapping("/profile/views/{targetId}")
    ProfileViewResponse recordProfileView(
            @PathVariable String targetId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/views/who-viewed-me")
    List<ProfileViewResponse> getWhoViewedMe(
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/views/who-i-viewed")
    List<WhoIViewedResponse> getWhoIViewed(
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/views/my-stats")
    ProfileViewStats getMyProfileViewStats(
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/views/stats")
    Object getProfileViewStats(
            @RequestParam(required = false) String userId,
            @RequestHeader("Authorization") String token);

    @GetMapping("/profile/views/stats/{userId}")
    PublicProfileViewStats getUserProfileViewStats(
            @PathVariable String userId,
            @RequestHeader("Authorization") String token);
}