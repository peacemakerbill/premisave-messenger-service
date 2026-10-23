package com.premisave.messenger.util;

import com.premisave.messenger.dto.response.UserSummaryResponse;

/** One rule for how a person's name is shown: full name first, then display name, then username. */
public final class UserNames {

    private UserNames() {
    }

    public static String full(UserSummaryResponse user) {
        if (user == null) return "Unknown User";
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String name = (first + " " + last).trim();
        if (!name.isEmpty()) return name;
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) return user.getDisplayName();
        if (user.getUsername() != null && !user.getUsername().isBlank()) return user.getUsername();
        return "Unknown User";
    }
}