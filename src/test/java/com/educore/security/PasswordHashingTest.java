package com.educore.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** D-07: DelegatingPasswordEncoder, bcrypt strength 12, legacy hashes match and are flagged for upgrade. */
class PasswordHashingTest {

    /** TEST DATA ONLY. */
    private static final String RAW = "hashing-test-only-value";

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void newHashesAreBcrypt12WithAnId() {
        String hash = encoder.encode(RAW);

        assertThat(hash).startsWith("{bcrypt}$2a$12$");
        assertThat(encoder.matches(RAW, hash)).isTrue();
        assertThat(encoder.matches(RAW + "x", hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
    }

    @Test
    void legacyUnprefixedBcryptHashesMatchAndNeedAnUpgrade() {
        String legacy = new BCryptPasswordEncoder(10).encode(RAW);

        assertThat(legacy).startsWith("$2a$10$");
        assertThat(encoder.matches(RAW, legacy)).isTrue();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();
    }

    @Test
    void prefixedLowCostHashesNeedAnUpgrade() {
        String weak = "{bcrypt}" + new BCryptPasswordEncoder(10).encode(RAW);

        assertThat(encoder.matches(RAW, weak)).isTrue();
        assertThat(encoder.upgradeEncoding(weak)).isTrue();
    }

    @Test
    void plaintextStoredValuesNeverMatch() {
        assertThat(encoder.matches(RAW, RAW)).isFalse();
        assertThat(encoder.matches(RAW, "{noop}" + RAW)).isFalse();
    }
}
