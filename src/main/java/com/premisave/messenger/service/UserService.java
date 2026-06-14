package com.premisave.messenger.service;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final AuthServiceClient authServiceClient;

    /**
     * Get single user summary
     */
    public UserSummaryResponse getUserSummary(String userId, String token) {
        try {
            return authServiceClient.getUserSummary(userId, token);
        } catch (Exception e) {
            log.warn("Failed to fetch user profile for {}: {}", userId, e.getMessage());
            return createFallbackUser(userId);
        }
    }

    /**
     * Bulk fetch (useful for groups later)
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