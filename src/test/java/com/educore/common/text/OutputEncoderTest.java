package com.educore.common.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputEncoderTest {

    @Test
    void markupAndAttributeBreakoutCharactersAreEscaped() {
        assertThat(OutputEncoder.html("<script>alert('x')</script>"))
                .isEqualTo("&lt;script&gt;alert(&#x27;x&#x27;)&lt;&#x2F;script&gt;");
        assertThat(OutputEncoder.html("\" onmouseover=\"x")).isEqualTo("&quot; onmouseover=&quot;x");
        assertThat(OutputEncoder.html("Tom & Jerry")).isEqualTo("Tom &amp; Jerry");
    }

    @Test
    void escapingIsNotUndoneByPreEncodedInput() {
        assertThat(OutputEncoder.html("&lt;b&gt;")).isEqualTo("&amp;lt;b&amp;gt;");
    }

    @Test
    void plainTextAndUnicodeAreKeptAndControlCharactersDropped() {
        assertThat(OutputEncoder.html("Ayşe Öztürk 2601005")).isEqualTo("Ayşe Öztürk 2601005");
        assertThat(OutputEncoder.html("a\u0000b\u0007c\td\ne")).isEqualTo("abc\td\ne");
        assertThat(OutputEncoder.html(null)).isEmpty();
    }
}
