package com.educore.auth;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Peppered username digest stored in {@code login_attempt.username_hash}: HMAC-SHA-256 keyed with
 * {@code EDUCORE_LOGIN_PEPPER}, hex encoded. Raw usernames are never written to that table.
 * <p>
 * The pepper is mandatory in {@code prod} (ProdStartupGuard) and must be at least
 * {@value #MIN_PEPPER_LENGTH} characters. Outside {@code prod} an unset pepper is replaced by a random
 * per-process value, which only means lockout counters do not survive a restart.
 */
@Component
public class UsernameHasher {

    static final int MIN_PEPPER_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(UsernameHasher.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    @Autowired
    public UsernameHasher(EduCoreProperties properties) {
        this(properties.security().login().usernamePepper());
    }

    UsernameHasher(String pepper) {
        byte[] keyBytes;
        if (pepper == null || pepper.isBlank()) {
            keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            log.warn("EDUCORE_LOGIN_PEPPER is not set; using a random per-process pepper "
                    + "(login lockout counters reset on restart). It is mandatory in prod.");
        } else if (pepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException("EDUCORE_LOGIN_PEPPER (property educore.security.login.username-pepper) "
                    + "must be at least " + MIN_PEPPER_LENGTH + " characters long.");
        } else {
            keyBytes = pepper.getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public String hash(String username) {
        return hmacHex(key, username);
    }

    /**
     * A keyed digest for another purpose than username hashing (domain separation, R-21): its key is
     * {@code HMAC-SHA-256(key = label, message = pepper)} (HKDF-Extract with the label as salt), so no input of
     * {@link #hash} (which is keyed with the pepper itself) can ever reproduce one of its outputs, and different
     * labels give independent keys. The pepper never leaves this class.
     */
    public KeyedDigest derive(String label) {
        try {
            Mac extract = Mac.getInstance(ALGORITHM);
            extract.init(new SecretKeySpec(label.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return new KeyedDigest(new SecretKeySpec(extract.doFinal(key.getEncoded()), ALGORITHM));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }

    /** HMAC-SHA-256 under a key derived by {@link #derive}; hex output. */
    public static final class KeyedDigest {

        private final SecretKeySpec key;

        private KeyedDigest(SecretKeySpec key) {
            this.key = key;
        }

        public String hex(String input) {
            return hmacHex(key, input);
        }

        @Override
        public String toString() {
            return "KeyedDigest[<redacted>]";
        }
    }

    private static String hmacHex(SecretKeySpec key, String input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }
}
