package com.educore.common.text;

/**
 * HTML-escapes text for any field the server renders into HTML (the API itself answers JSON only; this is for
 * HTML e-mails, webhook previews and HTML exports). Escapes {@code & < > " '} and {@code /}, the set that is
 * safe for both element content and quoted attribute values; control characters other than tab, LF and CR
 * are dropped. It is not a substitute for a URL or JavaScript encoder.
 */
public final class OutputEncoder {

    private OutputEncoder() {
    }

    /** The HTML-escaped form of {@code value}; {@code null} becomes the empty string. */
    public static String html(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> encoded.append("&amp;");
                case '<' -> encoded.append("&lt;");
                case '>' -> encoded.append("&gt;");
                case '"' -> encoded.append("&quot;");
                case '\'' -> encoded.append("&#x27;");
                case '/' -> encoded.append("&#x2F;");
                default -> {
                    if (c >= 0x20 && c != 0x7f || c == '\t' || c == '\n' || c == '\r') {
                        encoded.append(c);
                    }
                }
            }
        }
        return encoded.toString();
    }
}
