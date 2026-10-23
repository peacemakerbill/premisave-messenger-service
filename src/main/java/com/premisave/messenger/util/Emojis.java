package com.premisave.messenger.util;

import com.premisave.messenger.exception.HttpStatusException;

/** Checks that a reaction is a single short emoji, safe to store as a database key. */
public final class Emojis {

    public static final int MAX_LENGTH = 16;

    private Emojis() {
    }

    /** Returns the trimmed emoji, or throws a 400 when the value is not one. */
    public static String validate(String emoji) {
        String value = emoji == null ? "" : emoji.trim();
        boolean ok = !value.isEmpty()
                && value.length() <= MAX_LENGTH
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || c == '.' || c == '$')
                && value.codePoints().anyMatch(cp -> cp >= 0x2000);
        if (!ok) {
            throw HttpStatusException.badRequest("Reaction must be a single emoji");
        }
        return value;
    }
}