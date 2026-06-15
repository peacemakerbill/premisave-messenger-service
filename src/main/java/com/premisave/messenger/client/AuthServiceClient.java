package com.premisave.messenger.client;

import com.premisave.messenger.dto.response.UserSummaryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "auth-service", url = "${auth.service.url:http://localhost:8080}")
public interface AuthServiceClient {

    /**
     * Get summary of a specific user by ID
     */
    @GetMapping("/profile/user/{userId}")
    UserSummaryResponse getUserSummary(
            @PathVariable String userId,
            @RequestHeader("Authorization") String token
    );

    /**
     * Get current authenticated user's profile
     */
    @GetMapping("/profile/me")
    UserSummaryResponse getCurrentUser(@RequestHeader("Authorization") String token);

    /**
     * Search users by username, name, or email
     */
    @GetMapping("/profile/search")
    List<UserSummaryResponse> searchUsers(
            @RequestParam("query") String query,
            @RequestHeader("Authorization") String token
    );

    /**
     * Get all users with pagination
     */
    @GetMapping("/profile/all")
    List<UserSummaryResponse> getAllUsers(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestHeader("Authorization") String token
    );
}