package com.educore.common.logging;

import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in log output. Applied to every rendered log message and stack trace,
 * in the human-readable pattern ({@link PiiMaskingConverter}, {@link PiiMaskingThrowableConverter}) and in the
 * JSON format ({@link PiiMaskingJsonCustomizer}). Rules, in order:
 * <ol>
 *   <li>line and paragraph separators that are not CR/LF (NEL, U+2028, U+2029) and bidi controls become
 *       {@code _} (JSON escapes CR/LF but not these, and log viewers break or reorder lines on them);</li>
 *   <li>bean validation's {@code rejected value [...]} (Spring's DEBUG output of a binding error, which can
 *       quote a submitted password) becomes {@code rejected value [[REDACTED]]};</li>
 *   <li>{@code key=value} / {@code "key":"value"} where the key ends in password, passwd, pwd, secret, token,
 *       authorization, cookie or api-key (case-insensitive, e.g. {@code newPassword}, {@code refreshToken},
 *       {@code Set-Cookie}): the value (a whole quoted string, or everything up to the next separator, including
 *       a {@code Bearer}/{@code Basic} scheme) becomes {@value #REDACTED};</li>
 *   <li>JSON Web Tokens ({@code eyJ...}.{@code ...}.{@code ...}): {@value #REDACTED_JWT};</li>
 *   <li>{@code Bearer <token>} / {@code Basic <credentials>}: the credentials become {@value #REDACTED};</li>
 *   <li>e-mail addresses: first character of the local part, then {@code ***@domain};</li>
 *   <li>tokens shaped like the temporary passwords issued to students (exactly 24 characters of the generator's
 *       alphabet with an upper-case letter, a lower-case letter, a digit and a symbol): {@value #REDACTED};</li>
 *   <li>opaque tokens (32 or more URL-safe base64 or hex characters mixing letters and digits, e.g. refresh
 *       tokens, hashes, keys; UUIDs are kept so request ids stay searchable): {@value #REDACTED};</li>
 *   <li>IPv4 addresses: the last octet becomes {@code ***};</li>
 *   <li>student numbers: labelled ({@code studentNumber=12345678}) 4 to 12 digits, and any stand-alone run of
 *       6 to 12 digits, keep their last two digits ({@code ******78}). Shorter bare numbers (ports, years,
 *       counts) are left alone.</li>
 * </ol>
 */
public final class PiiMasking {

    static final String REDACTED = "[REDACTED]";
    static final String REDACTED_JWT = "[REDACTED-JWT]";

    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final String TOKEN_CHARS = "[^\\s\"',;&}\\]]";
    /** Alphabet of generated temporary passwords (AccountCredentialService: no 0/O, 1/l/I). */
    private static final String PASSWORD_CHARS = "[A-HJ-NP-Za-km-z2-9!#$%&*+=?@^_-]";
    private static final int TEMPORARY_PASSWORD_LENGTH = 24;
    private static final String PASSWORD_STOP = "[^\\s\"',;)\\]}]";

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("[\\u0085\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]"),
                    m -> String.valueOf(LogSanitizer.REPLACEMENT)),
            new Rule(Pattern.compile("(?s)(rejected value \\[).*?(\\]; codes \\[)"),
                    m -> m.group(1) + REDACTED + m.group(2)),
            new Rule(Pattern.compile("(rejected value \\[)(?!\\[REDACTED\\]\\])[^\\]]*(\\])"),
                    m -> m.group(1) + REDACTED + m.group(2)),
            new Rule(Pattern.compile("(?i)([A-Za-z_-]*(?:password|passwd|pwd|secret|token|authorization|cookie"
                    + "|api[_-]?key)[\"']?\\s*[:=]\\s*)(?:(\")(?:[^\"\\\\]|\\\\.)*\"|(')[^']*'|(?:(?:bearer|basic)\\s+)?"
                    + TOKEN_CHARS + "+)"),
                    m -> {
                        String quote = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : "";
                        return m.group(1) + quote + REDACTED + quote;
                    }),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"),
                    m -> REDACTED_JWT),
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+(?!\\[REDACTED)[A-Za-z0-9._~+/=-]+"),
                    m -> m.group(1) + " " + REDACTED),
            new Rule(Pattern.compile("(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@"
                    + "([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})\\b"),
                    m -> m.group(1) + "***@" + m.group(2)),
            new Rule(Pattern.compile("(?<=^|[\\s\"'=:(\\[{,])"
                    + "(?=" + PASSWORD_STOP + "*[A-Z])(?=" + PASSWORD_STOP + "*[a-z])"
                    + "(?=" + PASSWORD_STOP + "*[0-9])(?=" + PASSWORD_STOP + "*[!#$%&*+=?@^_-])"
                    + PASSWORD_CHARS + "{" + TEMPORARY_PASSWORD_LENGTH + "}(?=$|[\\s\"',;)\\]}])"),
                    m -> REDACTED),
            new Rule(Pattern.compile("(?<![A-Za-z0-9_-])"
                    + "(?![A-Za-z0-9_-]*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "(?![A-Za-z0-9_-]))"
                    + "(?=[A-Za-z0-9_-]*[0-9])(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])"),
                    m -> REDACTED),
            new Rule(Pattern.compile("(?<![0-9.])(" + OCTET + "\\." + OCTET + "\\." + OCTET + ")\\." + OCTET
                    + "(?![0-9]|\\.[0-9])"),
                    m -> m.group(1) + ".***"),
            new Rule(Pattern.compile("(?i)(student[_ -]?(?:number|no)[\"']?\\s*[:=]\\s*[\"']?)([0-9]{2,10})([0-9]{2})"
                    + "(?![0-9])"),
                    m -> m.group(1) + "*".repeat(m.group(2).length()) + m.group(3)),
            new Rule(Pattern.compile("(?<![A-Za-z0-9_.*-])([0-9]{4,10})([0-9]{2})(?![A-Za-z0-9_.-])"),
                    m -> "*".repeat(m.group(1).length()) + m.group(2)));

    private PiiMasking() {
    }

    /** {@code text} with every rule applied; {@code null} stays {@code null}. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.apply(result);
        }
        return result;
    }

    private record Rule(Pattern pattern, Function<MatchResult, String> replacement) {

        String apply(String text) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return text;
            }
            matcher.reset();
            return matcher.replaceAll(match -> Matcher.quoteReplacement(replacement.apply(match)));
        }
    }
}
