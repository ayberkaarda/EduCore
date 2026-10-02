package com.educore.common.validation;

/**
 * Regular expressions for {@code @Pattern} constraints on request DTOs and parameters. Patterns that start
 * with {@code ^$|} also accept the empty string, which the services treat like an absent value; required
 * fields add {@code @NotBlank}.
 */
public final class InputPatterns {

    /** 4 to 12 digits, or empty (absent). */
    public static final String STUDENT_NUMBER = "^$|^[0-9]{4,12}$";

    /**
     * A personal name: starts with a letter; then letters, combining marks, spaces, apostrophes, dots and
     * hyphens (e.g. {@code Ayşe}, {@code O'Neil}, {@code Jean-Luc}, {@code J. R.}); or empty.
     */
    public static final String PERSON_NAME = "^$|^[\\p{L}][\\p{L}\\p{M} '.\\-]*$";

    /** A login name: letters, digits, dot, underscore, hyphen; 3 to 100 characters; or empty (generated). */
    public static final String USERNAME = "^$|^[\\p{L}\\p{N}._\\-]{3,100}$";

    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";

    /** A dotted-quad IPv4 address without leading zeros. */
    public static final String IPV4_ADDRESS = OCTET + "\\." + OCTET + "\\." + OCTET + "\\." + OCTET;

    /** An IPv4 address, or empty (no address). */
    public static final String OPTIONAL_IPV4 = "^$|^" + IPV4_ADDRESS + "$";

    /** An IP rule value: {@code a.b.c.d}, {@code a.b.c.d-e.f.g.h} or {@code a.b.c.d/0..32}. */
    public static final String IP_RULE_VALUE = "^" + IPV4_ADDRESS + "(-" + IPV4_ADDRESS + "|/(3[0-2]|[12]?[0-9]))?$";

    /**
     * Free text on one line. Rejects every code point of the Unicode categories Cc (controls, including CR,
     * LF, tab, NUL, DEL and the C1 range with U+0085 NEXT LINE), Cf (format: zero-width and bidi controls such
     * as U+200B, U+200E, U+202E, U+2066, U+FEFF), Zl (U+2028 LINE SEPARATOR), Zp (U+2029 PARAGRAPH SEPARATOR)
     * and Cs (unpaired surrogates).
     */
    public static final String SINGLE_LINE_TEXT = "^[^\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Cs}]*$";

    private InputPatterns() {
    }
}
