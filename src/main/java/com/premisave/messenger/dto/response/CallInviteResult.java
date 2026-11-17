package com.premisave.messenger.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** The result of adding people to a call. */
@Data
public class CallInviteResult {
    private CallResponse call;
    /** People whose phones are now ringing. */
    private List<String> invited = new ArrayList<>();
    /** People who were not added, with the reason. */
    private List<Skipped> skipped = new ArrayList<>();

    @Data
    public static class Skipped {
        private String userId;
        /** ALREADY_IN_CALL, NOT_REACHABLE, REMOVED, FULL. */
        private String reason;

        public Skipped() {
        }

        public Skipped(String userId, String reason) {
            this.userId = userId;
            this.reason = reason;
        }
    }
}
