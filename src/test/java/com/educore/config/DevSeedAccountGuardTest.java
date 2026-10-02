package com.educore.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The dev-seed identities come from the seed script itself, so the prod guard can never drift from it. */
class DevSeedAccountGuardTest {

    @Test
    void everySeededAccountIsRecognised() {
        assertThat(DevSeedAccountGuard.seedIdentities())
                .extracting(DevSeedAccountGuard.SeedIdentity::username)
                .containsExactly("admin", "ayberk", "ali");
        assertThat(DevSeedAccountGuard.seedIdentities()).allSatisfy(identity -> {
            assertThat(identity.passwordHash()).startsWith("$2a$10$").hasSize(60);
            assertThat(identity.studentNumber()).matches("900000[0-9]");
            assertThat(identity.toString()).doesNotContain(identity.passwordHash());
        });
    }
}
