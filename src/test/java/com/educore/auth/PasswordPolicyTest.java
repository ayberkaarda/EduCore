package com.educore.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Length, bcrypt byte limit and deny-list rules of {@link PasswordPolicy}. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void denyListContainsTheFullSecListsTopTenThousand() throws IOException {
        assertThat(policy.denyListSize()).isGreaterThanOrEqualTo(10_000);
        List<String> lines = new org.springframework.core.io.ClassPathResource(PasswordPolicy.DENY_LIST)
                .getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(10_000).startsWith("password", "REMOVED-DB-PASSWORD56", "REMOVED-DB-PASSWORD5678");
    }

    @Test
    void acceptsTwelveCharacterPasswordsNotOnTheList() {
        assertThat(policy.violations("quiet-orbit7")).isEmpty();
        assertThat(policy.violations("a sentence of several plain words")).isEmpty();
    }

    @Test
    void rejectsShortPasswords() {
        assertThat(policy.violations("eleven-char")).containsExactly("too_short");
        assertThat(policy.violations("")).containsExactly("too_short");
        assertThat(policy.violations(null)).containsExactly("too_short");
    }

    @Test
    void lengthIsCountedInCharactersNotUtf16Units() {
        // 12 emoji are 24 UTF-16 units and 48 UTF-8 bytes: long enough, within the bcrypt limit.
        assertThat(policy.violations("😀".repeat(12))).isEmpty();
        assertThat(policy.violations("😀".repeat(11))).containsExactly("too_short");
    }

    @Test
    void rejectsMoreThan128Characters() {
        assertThat(policy.violations("x".repeat(129))).containsExactly("too_long");
    }

    @Test
    void rejectsMoreThan72Utf8BytesBecauseBcryptCannotHashThem() {
        assertThat(policy.violations("y".repeat(72))).isEmpty();
        assertThat(policy.violations("y".repeat(73))).containsExactly("too_many_bytes");
        // 37 two-byte characters = 74 bytes.
        assertThat(policy.violations("ğ".repeat(36))).isEmpty();
        assertThat(policy.violations("ğ".repeat(37))).containsExactly("too_many_bytes");
    }

    @Test
    void rejectsDenyListedPasswordsCaseInsensitively() {
        assertThat(policy.violations("unbelievable")).containsExactly("common_password");
        assertThat(policy.violations("UnBelievable")).containsExactly("common_password");
        assertThat(policy.violations("password")).containsExactlyInAnyOrder("too_short", "common_password");
    }

    @Test
    void rejectsBlankPasswords() {
        assertThat(policy.violations(" ".repeat(12))).containsExactly("blank");
    }

    @Test
    void customDenyListIsUsed() {
        PasswordPolicy custom = new PasswordPolicy(new ByteArrayResource(
                "\nCorrectHorseBattery\n  \n".getBytes(StandardCharsets.UTF_8)));

        assertThat(custom.denyListSize()).isEqualTo(1);
        assertThat(custom.violations("correcthorsebattery")).containsExactly("common_password");
    }

    @Test
    void emptyDenyListFailsFast() {
        assertThatThrownBy(() -> new PasswordPolicy(new ByteArrayResource(new byte[0])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("empty");
    }
}
