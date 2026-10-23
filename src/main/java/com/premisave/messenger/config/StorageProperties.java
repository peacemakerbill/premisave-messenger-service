package com.premisave.messenger.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Binds the "storage" block of application.yml (fed by the STORAGE_* env vars).
 *
 * LOCAL   writes under storage.root.
 * NETWORK writes under storage.network.mount-path / storage.network.directory,
 *         a share that the operating system has already mounted. The service
 *         never mounts anything itself.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    public enum Mode { LOCAL, NETWORK }

    private Mode mode = Mode.LOCAL;

    /** Base directory in LOCAL mode. */
    private String root = "./data/messenger-storage";

    /** Public address of this service, used to build download links. */
    private String publicBaseUrl = "http://localhost:8081";

    /** Hard cap for one file. Keep spring.servlet.multipart.max-file-size in step with it. */
    private DataSize maxFileSize = DataSize.ofMegabytes(50);

    /** How long a signed download link stays valid. */
    private Duration urlTtl = Duration.ofHours(6);

    /** Uploads that never got attached to a message are removed after this. */
    private Duration unclaimedTtl = Duration.ofHours(24);

    /** HMAC key for signed links. Falls back to the JWT secret when blank. */
    private String signingSecret;

    private Network network = new Network();

    @Getter
    @Setter
    public static class Network {
        private String protocol = "SMB";
        private String host;
        private String share;
        private String mountPath;
        private String directory = "premisave-messenger";
        private boolean requireMount = true;
    }
}
