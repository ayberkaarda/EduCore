package com.educore.webhook;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets at rest with AES-256-GCM under {@code EDUCORE_ENCRYPTION_KEY} (base64 of exactly 32 bytes).
 * Stored form: {@code v1:} + base64(12-byte random IV || ciphertext || 128-bit tag); the constant
 * {@value #AAD} is bound as additional authenticated data, so a value encrypted for another purpose does not
 * decrypt here. Any change of the stored value makes decryption fail.
 * <p>
 * Outside {@code prod} (where {@code ProdStartupGuard} requires the key) a missing key is replaced by a random
 * per-process key, with a warning: secrets stored with it cannot be decrypted after a restart.
 */
@Component
public class SecretCipher {

    static final String PREFIX = "v1:";
    static final String AAD = "educore:webhook-secret:v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretCipher(EduCoreProperties properties) {
        this(properties.crypto().encryptionKey());
    }

    SecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("EDUCORE_ENCRYPTION_KEY is not set; using a random per-process key. "
                    + "Webhook secrets stored now cannot be decrypted after a restart.");
            byte[] generated = new byte[KEY_BYTES];
            random.nextBytes(generated);
            this.key = new SecretKeySpec(generated, "AES");
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("EDUCORE_ENCRYPTION_KEY must be base64 (generate it with "
                    + "`openssl rand -base64 32`)");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("EDUCORE_ENCRYPTION_KEY must decode to exactly 32 bytes "
                    + "(generate it with `openssl rand -base64 32`)");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** @throws IllegalStateException when the value is malformed, was encrypted under another key or was altered */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Encrypted secret has an unknown format");
        }
        byte[] all;
        try {
            all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Encrypted secret is not base64");
        }
        if (all.length <= IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Encrypted secret is truncated");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encrypted secret could not be decrypted (wrong key or altered value)");
        }
    }
}
