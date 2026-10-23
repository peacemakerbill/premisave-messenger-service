package com.premisave.messenger.security;

import com.premisave.messenger.config.StorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs download links so an image tag or a Flutter Image.network can load
 * a file without carrying an Authorization header. A link is only handed
 * out inside a message the caller was allowed to read, and stops working
 * after storage.url-ttl.
 */
@Component
public class AttachmentUrlSigner {

    private final StorageProperties props;
    private final byte[] key;

    public AttachmentUrlSigner(StorageProperties props, @Value("${jwt.secret}") String jwtSecret) {
        this.props = props;
        String secret = props.getSigningSecret() != null && !props.getSigningSecret().isBlank()
                ? props.getSigningSecret()
                : jwtSecret;
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String signedUrl(String attachmentId) {
        long exp = Instant.now().plus(props.getUrlTtl()).getEpochSecond();
        String base = props.getPublicBaseUrl().replaceAll("/+$", "");
        return base + "/api/attachments/" + attachmentId + "/content?exp=" + exp + "&sig=" + sign(attachmentId, exp);
    }

    public boolean isValid(String attachmentId, Long exp, String sig) {
        if (exp == null || sig == null || sig.isBlank()) return false;
        if (Instant.now().getEpochSecond() > exp) return false;
        byte[] expected = sign(attachmentId, exp).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String attachmentId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] raw = mac.doFinal((attachmentId + "." + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
