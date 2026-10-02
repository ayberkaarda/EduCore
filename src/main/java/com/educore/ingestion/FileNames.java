package com.educore.ingestion;

import java.util.Locale;
import java.util.Optional;

/**
 * Sanitises client- or filesystem-supplied CSV file names. The result contains only {@code [A-Za-z0-9._-]},
 * has no directory part (anything up to the last {@code /} or {@code \} is dropped, so {@code ../} cannot
 * traverse), does not start with a dot, is at most {@value #MAX_LENGTH} characters and ends with
 * {@code .csv}.
 */
public final class FileNames {

    static final int MAX_LENGTH = 100;
    static final String EXTENSION = ".csv";

    /** Name used for the job log and the moved file when the original name cannot be sanitised. */
    static final String FALLBACK = "invalid-name.csv";

    private FileNames() {
    }

    /** The sanitised name, or empty when nothing usable remains or the name does not end with {@code .csv}. */
    public static Optional<String> sanitize(String original) {
        if (original == null) {
            return Optional.empty();
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        if (!name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            return Optional.empty();
        }
        String stem = name.substring(0, name.length() - EXTENSION.length())
                .replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("^\\.+", "")
                .replaceAll("\\.{2,}", ".");
        if (stem.isEmpty() || stem.chars().allMatch(c -> c == '_' || c == '.' || c == '-')) {
            return Optional.empty();
        }
        int maxStem = MAX_LENGTH - EXTENSION.length();
        if (stem.length() > maxStem) {
            stem = stem.substring(0, maxStem);
        }
        return Optional.of(stem + EXTENSION);
    }
}
