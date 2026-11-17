package com.premisave.messenger.dto.response;

/** The result of trying a storage setting without saving it. {@code problem} says what to fix, and is null when it is fine. */
public record StorageCheckResponse(boolean ok, String problem, String location, Long freeMb) {
}
