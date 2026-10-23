package com.premisave.messenger.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds the "status" block of application.yml (fed by the STATUS_* env vars). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "status")
public class StatusProperties {

    /** The longest a status can stay, in hours. Nobody can choose more than this. */
    private int maxHours = 24;

    /** How long a status stays when the person does not choose, in hours. Never above maxHours. */
    private int defaultHours = 24;

    /** The most characters in a text status or a caption. */
    private int textMaxLength = 700;

    /** The most characters in a comment on a status. */
    private int commentMaxLength = 300;

    /** When true, followers can also open a person's past statuses. The owner always can. */
    private boolean pastVisibleToFollowers = false;

    /** The hours a person may choose from: 1 up to the larger of 1 and maxHours. */
    public int effectiveMax() {
        return Math.max(1, maxHours);
    }

    public int effectiveDefault() {
        return Math.min(Math.max(1, defaultHours), effectiveMax());
    }
}