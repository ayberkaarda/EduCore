package com.educore.common.logging;

/**
 * Makes a user-controlled value safe to place in a log line.
 * <ul>
 *   <li>every control character (CR, LF, TAB, NUL, NEL ...), Unicode line/paragraph separator, format
 *       character (zero-width, bidi overrides) and unpaired surrogate is replaced by {@code _}, so a value can
 *       neither start a forged log line nor visually reorder one; the replacement keeps the tampering
 *       visible;</li>
 *   <li>the result is capped at {@value #MAX_LENGTH} characters: longer values keep their first
 *       {@code MAX_LENGTH - 3} characters followed by {@code ...}.</li>
 * </ul>
 * Use it for every value that comes from a request (usernames, header values, file names, free text):
 * {@code log.info("Login failed (username={})", LogSanitizer.sanitize(username))}. Secrets and personal
 * data are additionally masked by {@link PiiMasking} at output time; sanitising does not make a password
 * loggable.
 */
public final class LogSanitizer {

    public static final int MAX_LENGTH = 200;
    static final char REPLACEMENT = '_';
    private static final String ELLIPSIS = "...";

    private LogSanitizer() {
    }

    /** {@code value} made single-line and capped at {@value #MAX_LENGTH} characters; {@code "null"} for null. */
    public static String sanitize(Object value) {
        return sanitize(value, MAX_LENGTH);
    }

    /**
     * {@code value} made single-line and capped at {@code maxLength} characters.
     *
     * @param maxLength at least 4 (room for one character and the ellipsis)
     */
    public static String sanitize(Object value, int maxLength) {
        if (maxLength < ELLIPSIS.length() + 1) {
            throw new IllegalArgumentException("maxLength must be at least " + (ELLIPSIS.length() + 1));
        }
        if (value == null) {
            return "null";
        }
        String text = value.toString();
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxLength + 1));
        int i = 0;
        // Copy at most maxLength + 1 characters: enough to know whether the value has to be truncated.
        while (i < text.length() && out.length() <= maxLength) {
            int codePoint = text.codePointAt(i);
            if (isUnsafe(codePoint)) {
                out.append(REPLACEMENT);
            } else {
                out.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        if (out.length() <= maxLength && i >= text.length()) {
            return out.toString();
        }
        int keep = maxLength - ELLIPSIS.length();
        if (Character.isHighSurrogate(out.charAt(keep - 1))) {
            keep--;
        }
        out.setLength(keep);
        return out.append(ELLIPSIS).toString();
    }

    static boolean isUnsafe(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
            default -> false;
        };
    }
}
