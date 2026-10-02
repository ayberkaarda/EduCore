package com.educore.common.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogSanitizerTest {

    @Test
    void plainValuesAreUnchanged() {
        assertThat(LogSanitizer.sanitize("ayse.yilmaz_42")).isEqualTo("ayse.yilmaz_42");
        assertThat(LogSanitizer.sanitize("Ayşe Çelik O'Neil")).isEqualTo("Ayşe Çelik O'Neil");
        assertThat(LogSanitizer.sanitize("")).isEmpty();
    }

    @Test
    void nullBecomesTheWordNull() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("null");
    }

    @Test
    void crLfCannotStartAForgedLine() {
        String forged = "admin\r\n2026-10-01T10:00:00Z INFO LOGIN_OK username=admin";

        String sanitized = LogSanitizer.sanitize(forged);

        assertThat(sanitized).isEqualTo("admin__2026-10-01T10:00:00Z INFO LOGIN_OK username=admin");
        assertThat(sanitized).doesNotContain("\r").doesNotContain("\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t", "\u0000", "\u0007", "\u001b", "\u007f", "\u0085", "\u2028", "\u2029", "\u200b",
            "\u202e", "\ufeff", "\ud800"})
    void everyControlSeparatorAndFormatCharacterIsReplaced(String unsafe) {
        assertThat(LogSanitizer.sanitize("a" + unsafe + "b")).isEqualTo("a_b");
    }

    @Test
    void supplementaryCharactersAreKept() {
        assertThat(LogSanitizer.sanitize("ok \uD83D\uDE00")).isEqualTo("ok \uD83D\uDE00");
    }

    @Test
    void valuesUpToTheLimitAreKeptWhole() {
        String exactly = "x".repeat(LogSanitizer.MAX_LENGTH);

        assertThat(LogSanitizer.sanitize(exactly)).isEqualTo(exactly);
    }

    @Test
    void longerValuesAreCappedAtTheLimitWithAnEllipsis() {
        String sanitized = LogSanitizer.sanitize("y".repeat(10_000));

        assertThat(sanitized).hasSize(LogSanitizer.MAX_LENGTH).endsWith("...");
        assertThat(sanitized).startsWith("y".repeat(LogSanitizer.MAX_LENGTH - 3));
    }

    @Test
    void cappingNeverSplitsASurrogatePair() {
        String value = "a".repeat(LogSanitizer.MAX_LENGTH - 4) + "\uD83D\uDE00".repeat(10);

        String sanitized = LogSanitizer.sanitize(value);

        assertThat(sanitized).endsWith("...").hasSizeLessThanOrEqualTo(LogSanitizer.MAX_LENGTH);
        String body = sanitized.substring(0, sanitized.length() - 3);
        assertThat(Character.isHighSurrogate(body.charAt(body.length() - 1))).isFalse();
    }

    @Test
    void customLimitIsHonoured() {
        assertThat(LogSanitizer.sanitize("abcdefghij", 8)).isEqualTo("abcde...");
        assertThat(LogSanitizer.sanitize(12345, 8)).isEqualTo("12345");
        assertThatThrownBy(() -> LogSanitizer.sanitize("abc", 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controlCharactersAreReplacedBeforeCapping() {
        String sanitized = LogSanitizer.sanitize("\n".repeat(500));

        assertThat(sanitized).hasSize(LogSanitizer.MAX_LENGTH).doesNotContain("\n");
    }
}
