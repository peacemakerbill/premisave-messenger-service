package com.premisave.messenger.service;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final AuthServiceClient authServiceClient;

    /**
     * Get single user summary - Fixed token handling
     */
    public UserSummaryResponse getUserSummary(String userId, String token) {
        try {
            // Ensure token is properly formatted
            if (token == null || !token.startsWith("Bearer ")) {
                token = "Bearer " + token; // fallback if only userId was passed
            }
            
            log.debug("Fetching user {} with token length: {}", userId, token.length());
            return authServiceClient.getUserSummary(userId, token);
        } catch (Exception e) {
            log.error("Failed to fetch user profile for {} | Token: {}", userId, token, e);
            return createFallbackUser(userId);
        }
    }

    /**
     * Search users
     */
    public List<UserSummaryResponse> searchUsers(String query, String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                token = "Bearer " + token;
            }
            log.debug("Searching users with query: '{}' | Token length: {}", query, token.length());
            return authServiceClient.searchUsers(query, token);
        } catch (Exception e) {
            log.error("User search failed for query '{}'", query, e);
            return List.of();
        }
    }

    /**
     * Get all users (paginated)
     */
    public List<UserSummaryResponse> getAllUsers(int page, int size, String token) {
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                token = "Bearer " + token;
            }
            log.debug("Fetching all users (page={}, size={}) | Token length: {}", page, size, token.length());
            return authServiceClient.getAllUsers(page, size, token);
        } catch (Exception e) {
            log.error("Failed to fetch all users", e);
            return List.of();
        }
    }

    /**
     * Bulk fetch
     */
    public Map<String, UserSummaryResponse> getUsersSummary(Set<String> userIds, String token) {
        return userIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> getUserSummary(id, token)
                ));
    }

    private UserSummaryResponse createFallbackUser(String userId) {
        UserSummaryResponse fallback = new UserSummaryResponse();
        fallback.setId(userId);
        fallback.setUsername("Unknown");
        fallback.setDisplayName("Unknown User");
        fallback.setProfilePictureUrl(null);
        return fallback;
    }
}