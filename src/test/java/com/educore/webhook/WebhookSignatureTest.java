package com.educore.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code X-EduCore-Signature: v1=hex(HMAC-SHA256(secret, timestamp + "." + rawBody))}, checked against a
 * reference value computed independently with
 * {@code printf '%s' '1700000000.<body>' | openssl dgst -sha256 -hmac '<secret>' -hex}.
 */
class WebhookSignatureTest {

    /** TEST DATA ONLY. */
    private static final String SECRET = "whsec_unit-test-only";
    private static final byte[] BODY = "{\"event\":\"webhook.test\",\"id\":\"d1\"}".getBytes(StandardCharsets.UTF_8);
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final String EXPECTED = "v1=282395e8078b5d02ff45bbf1f5f25f7743716ea6a2c0770e9f5d57e3f01a01bd";

    @Test
    void signsTimestampDotRawBodyWithHmacSha256() {
        assertThat(WebhookSigner.sign(SECRET, TIMESTAMP, BODY)).isEqualTo(EXPECTED);
    }

    @Test
    void verifiesWithinTheToleranceWindow() {
        Instant now = Instant.ofEpochSecond(TIMESTAMP + 299);

        assertThat(WebhookSigner.verify(SECRET, Long.toString(TIMESTAMP), BODY, EXPECTED, now,
                WebhookSigner.DEFAULT_TOLERANCE)).isTrue();
    }

    @Test
    void rejectsAlteredBodyTimestampSecretOrFormat() {
        Instant now = Instant.ofEpochSecond(TIMESTAMP);
        Duration tolerance = WebhookSigner.DEFAULT_TOLERANCE;
        byte[] altered = "{\"event\":\"webhook.test\",\"id\":\"d2\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSigner.verify(SECRET, Long.toString(TIMESTAMP), altered, EXPECTED, now, tolerance)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(TIMESTAMP + 1), BODY, EXPECTED, now, tolerance)).isFalse();
        assertThat(WebhookSigner.verify("whsec_other", Long.toString(TIMESTAMP), BODY, EXPECTED, now, tolerance)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(TIMESTAMP), BODY, EXPECTED.substring(3), now, tolerance))
                .isFalse();
        assertThat(WebhookSigner.verify(SECRET, Long.toString(TIMESTAMP), BODY, EXPECTED.toUpperCase(), now, tolerance))
                .isFalse();
        assertThat(WebhookSigner.verify(SECRET, "not-a-number", BODY, EXPECTED, now, tolerance)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, null, BODY, EXPECTED, now, tolerance)).isFalse();
    }

    @Test
    void rejectsTimestampsOutsideFiveMinutes() {
        String timestamp = Long.toString(TIMESTAMP);

        assertThat(WebhookSigner.verify(SECRET, timestamp, BODY, EXPECTED, Instant.ofEpochSecond(TIMESTAMP + 301),
                WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, timestamp, BODY, EXPECTED, Instant.ofEpochSecond(TIMESTAMP - 301),
                WebhookSigner.DEFAULT_TOLERANCE)).isFalse();
    }

    @Test
    void secretsAreEncryptedWithAes256GcmAndTamperingIsDetected() {
        // TEST DATA ONLY: base64 of the ASCII text "educore-test-only-encryption-key" (32 bytes).
        SecretCipher cipher = new SecretCipher("ZWR1Y29yZS10ZXN0LW9ubHktZW5jcnlwdGlvbi1rZXk="); // gitleaks:allow

        String first = cipher.encrypt(SECRET);
        String second = cipher.encrypt(SECRET);

        assertThat(first).startsWith("v1:").doesNotContain(SECRET).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(SECRET);
        char[] chars = first.toCharArray();
        chars[10] = chars[10] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> cipher.decrypt(new String(chars))).isInstanceOf(IllegalStateException.class);
        SecretCipher otherKey = new SecretCipher("b3RoZXItdGVzdC1vbmx5LWVuY3J5cHRpb24ta2V5LXg="); // gitleaks:allow
        assertThatThrownBy(() -> otherKey.decrypt(first)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptionKeyMustBe32Base64Bytes() {
        assertThatThrownBy(() -> new SecretCipher("c2hvcnQ=")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDUCORE_ENCRYPTION_KEY").hasMessageNotContaining("c2hvcnQ");
        assertThatThrownBy(() -> new SecretCipher("not base64!")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDUCORE_ENCRYPTION_KEY");
    }

    @Test
    void retryBackoffDoublesUpToTheCapWithAtMostTwentyPercentJitter() {
        Duration initial = Duration.ofSeconds(30);
        Duration max = Duration.ofMinutes(5);

        assertThat(WebhookDispatcher.backoff(1, initial, max, 0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(WebhookDispatcher.backoff(2, initial, max, 0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(WebhookDispatcher.backoff(3, initial, max, 0)).isEqualTo(Duration.ofSeconds(120));
        assertThat(WebhookDispatcher.backoff(4, initial, max, 0)).isEqualTo(Duration.ofSeconds(240));
        assertThat(WebhookDispatcher.backoff(5, initial, max, 0)).isEqualTo(Duration.ofMinutes(5));
        assertThat(WebhookDispatcher.backoff(2, initial, max, 0.999)).isBetween(Duration.ofSeconds(60),
                Duration.ofSeconds(72));
    }
}
