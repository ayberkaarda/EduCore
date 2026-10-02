package com.educore.course;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Course slugs: the public URL key of a course ({@code /courses/<slug>}). The rules match the backfill in
 * {@code V40__course_public_fields.sql}: letters are transliterated to ASCII (Turkish dotless {@code ı}
 * included), lowercased, every run of other characters becomes one hyphen, the stem is cut to
 * {@value #STEM_LENGTH} characters and trimmed of hyphens ({@code course} when nothing is left).
 */
public final class CourseSlugs {

    /** Lowercase ASCII letters and digits in hyphen-separated groups. */
    public static final String PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    public static final int MAX_LENGTH = 80;

    static final int STEM_LENGTH = 50;

    private static final String FALLBACK = "course";

    private CourseSlugs() {
    }

    /** The slug stem derived from a course name. */
    public static String stem(String name) {
        String ascii = Normalizer.normalize(name.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String hyphenated = trimHyphens(ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        String stem = trimHyphens(hyphenated.substring(0, Math.min(STEM_LENGTH, hyphenated.length())));
        return stem.isEmpty() ? FALLBACK : stem;
    }

    /**
     * The first free slug for {@code name}: the stem itself, then {@code stem-2}, {@code stem-3}, ...
     *
     * @param taken whether a slug is already used by another course
     */
    public static String unique(String name, Predicate<String> taken) {
        String stem = stem(name);
        String candidate = stem;
        for (int n = 2; taken.test(candidate); n++) {
            candidate = stem + "-" + n;
        }
        return candidate;
    }

    private static String trimHyphens(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
