package com.educore.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Peppered username digests stored in login_attempt. */
class UsernameHasherTest {

    private static final String PEPPER_A = "unit-test-only-pepper-aaaaaaaaaaaaaaaa";
    private static final String PEPPER_B = "unit-test-only-pepper-bbbbbbbbbbbbbbbb";

    @Test
    void sameUsernameAndPepperGiveTheSameHexDigest() {
        String hash = new UsernameHasher(PEPPER_A).hash("student-one");

        assertThat(hash).matches("[0-9a-f]{64}").doesNotContain("student");
        assertThat(new UsernameHasher(PEPPER_A).hash("student-one")).isEqualTo(hash);
        assertThat(new UsernameHasher(PEPPER_A).hash("student-two")).isNotEqualTo(hash);
    }

    @Test
    void digestDependsOnThePepper() {
        assertThat(new UsernameHasher(PEPPER_A).hash("student-one"))
                .isNotEqualTo(new UsernameHasher(PEPPER_B).hash("student-one"));
    }

    @Test
    void unsetPepperFallsBackToARandomPerInstanceValue() {
        UsernameHasher first = new UsernameHasher((String) null);
        UsernameHasher second = new UsernameHasher("  ");

        assertThat(first.hash("student-one")).isEqualTo(first.hash("student-one"))
                .isNotEqualTo(second.hash("student-one"));
    }

    @Test
    void shortPepperFailsFastNamingTheVariable() {
        assertThatThrownBy(() -> new UsernameHasher("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EDUCORE_LOGIN_PEPPER")
                .hasMessageNotContaining("too-short");
    }
}
