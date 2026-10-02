package com.educore.common.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The patterns are evaluated with {@link Pattern}, exactly as Hibernate Validator evaluates {@code @Pattern}. */
class InputPatternsTest {

    private static final Pattern SINGLE_LINE = Pattern.compile(InputPatterns.SINGLE_LINE_TEXT);

    @ParameterizedTest
    @ValueSource(ints = {
            0x0000, 0x0009, 0x000A, 0x000D, 0x001B, 0x007F,   // C0 controls and DEL (Cc)
            0x0085, 0x009B,                                   // NEXT LINE, C1 CSI (Cc)
            0x2028, 0x2029,                                   // LINE / PARAGRAPH SEPARATOR (Zl, Zp)
            0x00AD, 0x200B, 0x200D, 0x200E, 0x200F, 0x202A, 0x202E, 0x2066, 0x2069, 0xFEFF, // format (Cf)
            0xFFF9, 0xE0001,                                  // interlinear annotation, LANGUAGE TAG (Cf)
            0xD800, 0xDFFF})                                  // unpaired surrogates (Cs)
    void singleLineTextRejectsControlFormatAndSeparatorCharacters(int codePoint) {
        String value = "Course " + new String(Character.toChars(codePoint)) + " name";

        assertThat(SINGLE_LINE.matcher(value).matches()).as("U+%04X", codePoint).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Algorithms 101", "Ayşe Öztürk – Dr.", "2026/1", "C++ & Java (Güz)", "数据结构", "📚 Reading",
            "", "a b"})
    void singleLineTextAcceptsOrdinaryText(String value) {
        assertThat(SINGLE_LINE.matcher(value).matches()).as(value).isTrue();
    }
}
