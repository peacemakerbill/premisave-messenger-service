package com.premisave.messenger.security;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.exception.DownstreamServiceException;
import com.premisave.messenger.exception.HttpStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns an Authorization header into the caller's real user (Mongo id and
 * email) through auth-service /profile/me.
 *
 * The JWT subject is the EMAIL, so Authentication.getName() on a REST
 * request is an email, never a user id. Anything that compares against
 * chat participants must go through here.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

    private final AuthServiceClient authServiceClient;

    public UserSummaryResponse resolve(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw HttpStatusException.unauthorized("Missing or invalid Authorization header");
        }
        UserSummaryResponse user;
        try {
            user = authServiceClient.getCurrentUser(authHeader);
        } catch (feign.FeignException e) {
            if (e.status() == 401 || e.status() == 403) {
                throw HttpStatusException.unauthorized("Token was rejected by auth-service");
            }
            throw new DownstreamServiceException("auth-service", "Failed to resolve current user", e);
        }
        if (user == null || user.getId() == null) {
            throw HttpStatusException.unauthorized("Unable to resolve current user from token");
        }
        return user;
    }
}
