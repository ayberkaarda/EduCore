package com.educore.security;

import com.educore.config.EduCoreProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup validation of the current and previous JWT signing secrets. */
class JwtServiceTest {

    static String randomBase64(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    static EduCoreProperties.Jwt jwt(String secret, String previousSecret) {
        return new EduCoreProperties.Jwt(secret, previousSecret, "educore", "educore-api", Duration.ofMinutes(15));
    }

    private static IllegalStateException startupFailure(String secret, String previous) {
        return assertThrows(IllegalStateException.class, () -> new JwtService(jwt(secret, previous), Clock.systemUTC()));
    }

    @Test
    void missingSecretFailsStartup() {
        IllegalStateException nullSecret = startupFailure(null, null);
        IllegalStateException blankSecret = startupFailure("   ", null);
        assertTrue(nullSecret.getMessage().contains("EDUCORE_JWT_SECRET"));
        assertTrue(blankSecret.getMessage().contains("not configured"));
    }

    @Test
    void nonBase64SecretFailsStartupWithoutLeakingValueOrCause() {
        String valid = randomBase64(48);
        for (String malformed : List.of("not*base64!" + valid, valid + "#", "@" + valid)) {
            IllegalStateException e = startupFailure(malformed, null);
            assertTrue(e.getMessage().contains("EDUCORE_JWT_SECRET"));
            assertTrue(e.getMessage().contains("not valid base64"));
            assertFalse(e.getMessage().contains(valid));
            assertNull(e.getCause());
        }
    }

    @Test
    void tooShortSecretFailsStartup() {
        IllegalStateException e = startupFailure(randomBase64(31), null);
        assertTrue(e.getMessage().contains("31 bytes"));
        assertTrue(e.getMessage().contains("EDUCORE_JWT_SECRET"));
    }

    @Test
    void previousSecretIsOptional() {
        assertDoesNotThrow(() -> new JwtService(jwt(randomBase64(32), null), Clock.systemUTC()));
        assertDoesNotThrow(() -> new JwtService(jwt(randomBase64(32), ""), Clock.systemUTC()));
    }

    @Test
    void invalidPreviousSecretFailsStartupNamingItsVariable() {
        String valid = randomBase64(32);
        IllegalStateException tooShort = startupFailure(valid, randomBase64(16));
        IllegalStateException malformed = startupFailure(valid, "%%" + valid);
        assertTrue(tooShort.getMessage().contains("EDUCORE_JWT_SECRET_PREVIOUS"));
        assertTrue(tooShort.getMessage().contains("16 bytes"));
        assertTrue(malformed.getMessage().contains("EDUCORE_JWT_SECRET_PREVIOUS"));
        assertFalse(malformed.getMessage().contains(valid));
    }

    @Test
    void jwtPropertiesRedactBothSecrets() {
        String current = randomBase64(32);
        String previous = randomBase64(32);
        String text = jwt(current, previous).toString();
        assertFalse(text.contains(current));
        assertFalse(text.contains(previous));
        assertTrue(text.contains("<redacted>"));
    }
}
