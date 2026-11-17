package com.premisave.messenger.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Binds the "calls" block of application.yml (fed by the CALLS_* and LIVEKIT_* env vars). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "calls")
public class CallProperties {

    /** The address the apps connect to for audio and video, for example wss://live.premisave.com. */
    private String livekitUrl = "ws://localhost:7880";

    /** The address this service uses to reach LiveKit (to remove or mute people). Usually http://localhost:7880. */
    private String livekitApiUrl = "http://localhost:7880";

    /** Must match a key in the LiveKit server's config. */
    private String apiKey = "";

    /** At least 32 characters. Must match the LiveKit server's secret for that key. */
    private String apiSecret = "";

    /** How long phones ring before the call counts as missed. */
    private int ringTimeoutSeconds = 45;

    /** The most people in one call at the same time. */
    private int maxParticipants = 50;

    /** The most people a group call rings at once. Others in the group can still join from the chat. */
    private int ringLimit = 32;

    /** How long a join token works. It is only needed to connect. */
    private int tokenTtlMinutes = 120;

    /** What a call link starts with. The link is this, a slash and the code. */
    private String linkBaseUrl = "https://premisave.com/call";

    /** The longest a call link stays valid, in hours. */
    private int linkTtlHours = 24 * 7;

    /** Most characters in one message in a call's chat. */
    private int chatMaxLength = 2000;

    /**
     * The starting value of the recording switch. An administrator can change it from the dashboard, and from then on
     * their choice wins. Off by default, so nothing is recorded until someone decides to.
     */
    private boolean recordingEnabled = false;

    /** Where recordings are kept: LOCAL (a folder on this server) or NETWORK (a share that the operating system has mounted). */
    public enum StorageMode { LOCAL, NETWORK }

    private StorageMode recordingsMode = StorageMode.LOCAL;

    /** LOCAL: the folder the recorder writes videos to, as this service sees it. It must be the same folder the recorder writes to. */
    private String recordingsDir = "deploy/livekit/recordings";

    /** NETWORK: where the share is mounted, as this service sees it. The service never mounts anything itself. */
    private String recordingsNetworkMountPath = "";

    /** NETWORK: the folder inside the share for the videos. Blank means the mount path itself. */
    private String recordingsNetworkDirectory = "premisave-recordings";

    /** NETWORK: refuse to record unless the mount path really is a network share, so videos never fill the local disk by mistake. */
    private boolean recordingsNetworkRequireMount = true;

    /** Recording is refused when less than this many megabytes are free. 0 turns the check off. */
    private int recordingsMinFreeMb = 500;

    /** The same folder as the recorder sees it (the path inside the recorder's container). */
    private String recordingsEgressDir = "/out";

    /** How the video is laid out: speaker (the speaker big, screen shares in front) or grid. */
    private String recordingLayout = "speaker";

    /** How long a recording link works, in hours. Links are made again every time the list is loaded. */
    private int recordingLinkTtlHours = 6;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && apiSecret != null && apiSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public String linkFor(String code) {
        String base = linkBaseUrl == null ? "" : linkBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + code;
    }
}
