package com.educore.common.text;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Neutralises CSV/spreadsheet formula injection in exported cells. A cell whose first character, or first
 * character after leading whitespace or invisible format characters, is a formula trigger is prefixed with a
 * single quote, so spreadsheet applications show it as text instead of evaluating it. Triggers are
 * {@code =}, {@code +}, {@code -}, {@code @}, tab and carriage return, plus their fullwidth (U+FF1D, U+FF0B,
 * U+FF0D, U+FF20) and small-form (U+FE66, U+FE62, U+FE63, U+FE6B) variants and U+2212 MINUS SIGN, which some
 * spreadsheet locales normalise to the ASCII operator. Skipped leading characters are every Unicode space
 * ({@link Character#isSpaceChar}, e.g. U+00A0, U+2007, U+202F, U+3000), other whitespace such as LF, and
 * format characters (category Cf, e.g. U+200B, U+FEFF).
 * {@link #cell} additionally quotes the result for RFC 4180 output when it contains a separator, quote or line
 * break. Every CSV the application writes must pass each cell through {@link #cell} (or {@link #row}).
 * <p>
 * Trade-off: legitimate values such as negative numbers ({@code -5}) are also prefixed; exports are for
 * people and spreadsheets, and the quote is visible but harmless.
 */
public final class CsvSanitizer {

    private static final String TRIGGERS = "=+-@\t\r＝＋－＠﹦﹢﹣﹫−";

    private CsvSanitizer() {
    }

    /** {@code value} with a leading single quote when it could be read as a formula; {@code null} as empty. */
    public static String neutralise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int first = 0;
        while (first < value.length() && isSkippedLeadingCharacter(value.charAt(first))) {
            first++;
        }
        if (first < value.length() && TRIGGERS.indexOf(value.charAt(first)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    /** Characters a spreadsheet may ignore before a formula; tab and CR are triggers themselves. */
    private static boolean isSkippedLeadingCharacter(char c) {
        if (c == '\t' || c == '\r') {
            return false;
        }
        return Character.isSpaceChar(c) || Character.isWhitespace(c) || Character.getType(c) == Character.FORMAT;
    }

    /** A safe CSV field: neutralised, then enclosed in double quotes (inner quotes doubled) when needed. */
    public static String cell(String value) {
        String safe = neutralise(value);
        boolean needsQuotes = safe.indexOf(',') >= 0 || safe.indexOf(';') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        return needsQuotes ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    /** One CSV line (without line terminator) of safe cells separated by commas. */
    public static String row(List<String> values) {
        return values.stream().map(CsvSanitizer::cell).collect(Collectors.joining(","));
    }
}
