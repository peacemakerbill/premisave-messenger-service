package com.premisave.messenger.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds the "group-invite" block of application.yml (fed by the GROUP_INVITE_* env vars). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "group-invite")
public class GroupInviteProperties {

    /**
     * What the link starts with. The link is this, a slash and the code, for example
     * https://premisave.com/join/Qx3k... Point it at whatever opens the app or the web page.
     */
    private String linkBaseUrl = "https://premisave.com/join";

    /** The longest an admin can make a link last, in hours. A link can also have no expiry at all. */
    private int maxExpiryHours = 24 * 365;

    /** The most joins an admin can limit a link to. */
    private int maxUsesLimit = 100_000;

    public String linkFor(String code) {
        String base = linkBaseUrl == null ? "" : linkBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + code;
    }
}