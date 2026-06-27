package com.premisave.messenger.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic boolean status response for social relationship checks.
 * Wraps the Map<String, Boolean> returned by the auth-service
 * (e.g. {"liked": true}, {"following": false}, {"mutual": true}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialStatusResponse {

    private Boolean liked;
    private Boolean following;
    private Boolean reviewed;
    private Boolean mutual;

    /** Convenience — returns whichever flag is present and true. */
    public boolean isActive() {
        if (liked != null)     return liked;
        if (following != null) return following;
        if (reviewed != null)  return reviewed;
        if (mutual != null)    return mutual;
        return false;
    }
}