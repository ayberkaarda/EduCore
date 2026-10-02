package com.educore.ingestion;

/**
 * Masks a raw CSV line before it is stored or reported: in every run of letters and digits only the first
 * character is kept and the rest becomes {@code ***} (so neither the value nor its length is disclosed);
 * control characters become spaces and the result is cut to {@value #MAX_LENGTH} characters.
 * {@code "Ayşe,Yılmaz,20230017"} becomes {@code "A***,Y***,2***"}.
 */
public final class PiiMasker {

    static final int MAX_LENGTH = 500;

    private PiiMasker() {
    }

    public static String mask(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder masked = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        boolean inRun = false;
        boolean starred = false;
        for (int i = 0; i < raw.length() && masked.length() < MAX_LENGTH; ) {
            int codePoint = raw.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                if (!inRun) {
                    masked.appendCodePoint(codePoint);
                    inRun = true;
                    starred = false;
                } else if (!starred) {
                    masked.append("***");
                    starred = true;
                }
            } else {
                inRun = false;
                masked.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            }
        }
        return masked.length() > MAX_LENGTH ? masked.substring(0, MAX_LENGTH) : masked.toString();
    }
}
