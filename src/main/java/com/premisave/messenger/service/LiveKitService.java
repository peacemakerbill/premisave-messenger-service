package com.premisave.messenger.service;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.exception.HttpStatusException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only place that talks to the LiveKit media server.
 *
 * LiveKit carries the audio, video and screen sharing. This service never touches media: it signs the join tokens the apps
 * connect with, and asks LiveKit to remove or mute people when a host does that. A LiveKit token is a plain HS256 JWT and
 * the admin API is plain JSON over HTTP, so no LiveKit SDK is needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveKitService {

    private static final String TWIRP = "/twirp/livekit.RoomService/";

    private final CallProperties props;
    private final ObjectMapper objectMapper;

    private final RestClient http = RestClient.create();

    // ------------------------------------------------------------------ tokens

    /**
     * A token that lets one person join one room. The identity is their user id, so a second device of the same person
     * replaces the first, and the server can always map a LiveKit participant back to a user.
     *
     * @param metadata  JSON text other participants can read (picture, role)
     * @param attributes small key/value pairs other participants can read; the person can change their own (raise hand)
     */
    public String joinToken(String room, String identity, String name, Map<String, Object> metadata,
                            Map<String, String> attributes) {
        requireConfigured();
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("room", room);
        video.put("roomJoin", true);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);
        video.put("canUpdateOwnMetadata", true);

        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(props.getApiKey())
                .subject(identity)
                .claim("name", name == null ? identity : name)
                .claim("video", video)
                .notBefore(Date.from(now.minusSeconds(30)))
                .expiration(Date.from(now.plusSeconds(Math.max(1, props.getTokenTtlMinutes()) * 60L)));
        if (metadata != null && !metadata.isEmpty()) {
            builder.claim("metadata", objectMapper.writeValueAsString(metadata));
        }
        if (attributes != null && !attributes.isEmpty()) {
            builder.claim("attributes", attributes);
        }
        return builder.signWith(key(), Jwts.SIG.HS256).compact();
    }

    /** A short token for this service's own calls to the admin API. */
    private String adminToken(String room) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("roomAdmin", true);
        video.put("roomList", true);
        if (room != null) video.put("room", room);
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.getApiKey())
                .subject("messenger-service")
                .claim("video", video)
                .notBefore(Date.from(now.minusSeconds(30)))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(props.getApiSecret().getBytes(StandardCharsets.UTF_8));
    }

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CALLS_NOT_CONFIGURED",
                    "Calls are not set up on this server yet");
        }
    }

    // --------------------------------------------------------------- admin API

    /** Disconnects one person. Quietly does nothing when they are not in the room. */
    public void removeParticipant(String room, String identity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        body.put("identity", identity);
        post("RemoveParticipant", room, body);
    }

    /** Closes the room and disconnects everyone in it. */
    public void deleteRoom(String room) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        post("DeleteRoom", room, body);
    }

    /**
     * Mutes the microphone of everyone in the list. Returns how many were muted. An empty list (or null) means everyone
     * in the room except the people in {@code except}.
     */
    public int muteAudio(String room, Collection<String> only, Collection<String> except) {
        int muted = 0;
        for (Map<String, Object> participant : listParticipants(room)) {
            String identity = str(participant.get("identity"));
            if (identity == null) continue;
            if (only != null && !only.isEmpty() && !only.contains(identity)) continue;
            if (except != null && except.contains(identity)) continue;
            Object tracks = participant.get("tracks");
            if (!(tracks instanceof List<?> list)) continue;
            for (Object t : list) {
                if (!(t instanceof Map<?, ?> track)) continue;
                if (!isMicrophone(track) || Boolean.TRUE.equals(track.get("muted"))) continue;
                String sid = str(track.get("sid"));
                if (sid == null) continue;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("room", room);
                body.put("identity", identity);
                body.put("trackSid", sid);
                body.put("muted", true);
                if (post("MutePublishedTrack", room, body) != null) muted++;
            }
        }
        return muted;
    }

    /** The identities of the people LiveKit says are in the room right now. Null when LiveKit could not be asked. */
    public Set<String> identitiesInRoom(String room) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        Map<String, Object> answer = post("ListParticipants", room, body);
        if (answer == null) return null;
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> p : participantsOf(answer)) {
            String id = str(p.get("identity"));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private List<Map<String, Object>> listParticipants(String room) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room", room);
        Map<String, Object> answer = post("ListParticipants", room, body);
        return answer == null ? List.of() : participantsOf(answer);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> participantsOf(Map<String, Object> answer) {
        Object list = answer.get("participants");
        List<Map<String, Object>> out = new ArrayList<>();
        if (list instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** A track is the microphone when LiveKit calls it AUDIO (or 1) and its source is the microphone. */
    private static boolean isMicrophone(Map<?, ?> track) {
        String type = str(track.get("type"));
        boolean audio = "AUDIO".equalsIgnoreCase(type) || "1".equals(type);
        String source = str(track.get("source"));
        boolean mic = source == null || "MICROPHONE".equalsIgnoreCase(source) || "2".equals(source);
        return audio && mic;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * One call to LiveKit's admin API. Returns the answer, or null when it failed. A host action that cannot reach LiveKit
     * is logged and ignored: the call record is still correct, and the app keeps working.
     */
    private Map<String, Object> post(String method, String room, Map<String, Object> body) {
        if (!props.isConfigured()) return null;
        try {
            return http.post()
                    .uri(trim(props.getLivekitApiUrl()) + TWIRP + method)
                    .header("Authorization", "Bearer " + adminToken(room))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientException e) {
            // A 404 means the room or the person is already gone, which is what was wanted.
            log.warn("LiveKit {} for room {} failed: {}", method, room, e.getMessage());
            return null;
        }
    }

    private static String trim(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    // ---------------------------------------------------------------- webhooks

    /**
     * Checks that a webhook really came from our LiveKit server: the Authorization header is a JWT signed with the shared
     * secret that carries the SHA-256 of the body.
     */
    public boolean verifyWebhook(String authorization, String rawBody) {
        if (!props.isConfigured() || authorization == null || authorization.isBlank() || rawBody == null) return false;
        try {
            String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(props.getApiKey())
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
            String expected = claims.get("sha256", String.class);
            if (expected == null) return false;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawBody.getBytes(StandardCharsets.UTF_8));
            String actual = Base64.getEncoder().encodeToString(digest);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Rejected a LiveKit webhook: {}", e.getMessage());
            return false;
        }
    }
}
