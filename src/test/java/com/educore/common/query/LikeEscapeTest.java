package com.educore.common.query;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LikeEscapeTest {

    @Test
    void wildcardsAndTheEscapeCharacterAreEscaped() {
        assertThat(LikePatterns.escape("50%")).isEqualTo("50!%");
        assertThat(LikePatterns.escape("a_b")).isEqualTo("a!_b");
        assertThat(LikePatterns.escape("wow!")).isEqualTo("wow!!");
        assertThat(LikePatterns.escape("%_!")).isEqualTo("!%!_!!");
    }

    @Test
    void otherCharactersPassThroughUnchanged() {
        assertThat(LikePatterns.escape("O'Neil \\ [abc] ^$ *?")).isEqualTo("O'Neil \\ [abc] ^$ *?");
        assertThat(LikePatterns.escape(null)).isEmpty();
        assertThat(LikePatterns.escape("")).isEmpty();
    }

    @Test
    void containsWrapsTheTrimmedEscapedInputInWildcards() {
        assertThat(LikePatterns.contains("  ali ")).isEqualTo("%ali%");
        assertThat(LikePatterns.contains("%")).isEqualTo("%!%%");
        assertThat(LikePatterns.contains(null)).isEqualTo("%%");
    }

    /**
     * Evaluates the produced pattern with the SQL {@code LIKE ... ESCAPE '!'} semantics: the user's {@code %}
     * and {@code _} only match themselves, while the surrounding wildcards still match anything.
     */
    @Test
    void escapedPatternMatchesTheInputLiterally() {
        String pattern = LikePatterns.contains("5_%");

        assertThat(like("grade 5_% done", pattern)).isTrue();
        assertThat(like("grade 5a1 done", pattern)).isFalse();
        assertThat(like("5_%", pattern)).isTrue();
        assertThat(like("anything", LikePatterns.contains(""))).isTrue();
        assertThat(like("abc", LikePatterns.contains("%"))).isFalse();
    }

    private static boolean like(String value, String likePattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            if (c == LikePatterns.ESCAPE) {
                regex.append(Pattern.quote(String.valueOf(likePattern.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(value).matches();
    }
}
