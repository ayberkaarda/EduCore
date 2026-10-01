package com.educore.auth;

import com.educore.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure path for an unknown username performs the same bcrypt work as the path for an existing
 * account, whatever the cost of its stored hash. Work is measured by counting the bcrypt rounds the encoder
 * is asked to run (2^cost per check), not by wall-clock time.
 */
class CredentialVerifierTest {

    /** TEST DATA ONLY. */
    private static final String RAW = "verifier-test-only-value";

    /** Delegates to the application encoder and adds up 2^cost for every hash it verifies. */
    private static final class CountingEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new SecurityConfig().passwordEncoder();
        private final AtomicLong work = new AtomicLong();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            int cost = CredentialVerifier.cost(encodedPassword);
            work.addAndGet(cost < 0 ? 0 : 1L << cost);
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return delegate.upgradeEncoding(encodedPassword);
        }

        long take() {
            return work.getAndSet(0);
        }
    }

    private final CountingEncoder encoder = new CountingEncoder();
    private final CredentialVerifier verifier = new CredentialVerifier(encoder);

    @Test
    void unknownUserDummyHasTheSameStrengthAsNewHashes() {
        int encoderStrength = CredentialVerifier.cost(encoder.encode(RAW));

        assertThat(encoderStrength).isEqualTo(12);
        assertThat(verifier.targetCost()).isEqualTo(encoderStrength);
        assertThat(verifier.unknownUserDummyCost()).isEqualTo(encoderStrength);
    }

    @Test
    void unknownUserLegacyHashAndCurrentHashCostTheSameBcryptWork() {
        String current = encoder.encode(RAW);
        String legacy = new BCryptPasswordEncoder(10).encode(RAW);
        String prefixedLegacy = "{bcrypt}" + new BCryptPasswordEncoder(11).encode(RAW);
        long expected = 1L << 12;
        encoder.take();

        CredentialVerifier.Verification unknown = verifier.verify(RAW, null);
        long unknownWork = encoder.take();
        CredentialVerifier.Verification legacyWrong = verifier.verify(RAW + "x", legacy);
        long legacyWrongWork = encoder.take();
        CredentialVerifier.Verification legacyRight = verifier.verify(RAW, legacy);
        long legacyRightWork = encoder.take();
        CredentialVerifier.Verification prefixed = verifier.verify(RAW, prefixedLegacy);
        long prefixedWork = encoder.take();
        CredentialVerifier.Verification currentWrong = verifier.verify(RAW + "x", current);
        long currentWrongWork = encoder.take();

        assertThat(unknown.matched()).isFalse();
        assertThat(legacyWrong.matched()).isFalse();
        assertThat(legacyRight.matched()).isTrue();
        assertThat(prefixed.matched()).isTrue();
        assertThat(currentWrong.matched()).isFalse();
        assertThat(new long[] {unknownWork, legacyWrongWork, legacyRightWork, prefixedWork, currentWrongWork})
                .containsOnly(expected);
        assertThat(new long[] {unknown.work(), legacyWrong.work(), legacyRight.work(), prefixed.work(),
                currentWrong.work()}).containsOnly(expected);
    }

    @Test
    void nonBcryptStoredValuesNeverMatchAndCostAFullCheck() {
        encoder.take();

        CredentialVerifier.Verification plaintext = verifier.verify(RAW, RAW);

        assertThat(plaintext.matched()).isFalse();
        assertThat(encoder.take()).isEqualTo(1L << 12);
    }

    @Test
    void costIsReadWithAndWithoutPrefix() {
        assertThat(CredentialVerifier.cost(new BCryptPasswordEncoder(10).encode(RAW))).isEqualTo(10);
        assertThat(CredentialVerifier.cost("{bcrypt}" + new BCryptPasswordEncoder(5).encode(RAW))).isEqualTo(5);
        assertThat(CredentialVerifier.cost("not-a-hash")).isEqualTo(-1);
    }
}
