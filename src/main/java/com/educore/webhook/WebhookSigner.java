package com.educore.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Webhook request signatures. {@code X-EduCore-Signature: v1=<hex>} where
 * {@code hex = lowercase hex(HMAC-SHA256(key = UTF-8 bytes of the secret, message = timestamp + "." + rawBody))}
 * and {@code timestamp} is the {@code X-EduCore-Timestamp} header (unix seconds, as sent). Receivers recompute
 * the value, compare in constant time and reject timestamps more than {@link #DEFAULT_TOLERANCE} away from
 * their clock (docs/integrations/WEBHOOKS.md).
 */
public final class WebhookSigner {

    public static final String VERSION_PREFIX = "v1=";
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSigner() {
    }

    /** The {@code X-EduCore-Signature} header value for {@code rawBody} sent at {@code timestamp}. */
    public static String sign(String secret, long timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java platform", e);
        }
    }

    /**
     * Receiver-side check: the timestamp is within {@code tolerance} of {@code now} and the signature matches
     * (constant-time comparison).
     */
    public static boolean verify(String secret, String timestampHeader, byte[] rawBody, String signatureHeader,
                                 Instant now, Duration tolerance) {
        if (timestampHeader == null || signatureHeader == null || !signatureHeader.startsWith(VERSION_PREFIX)) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            return false;
        }
        byte[] expected = sign(secret, timestamp, rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signatureHeader.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
