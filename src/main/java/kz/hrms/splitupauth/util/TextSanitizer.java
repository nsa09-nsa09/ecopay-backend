package kz.hrms.splitupauth.util;

import java.util.regex.Pattern;

/**
 * Server-side sanitization for free-text user fields (review text, ticket
 * subject/message). Defense-in-depth on top of React's default escaping, per
 * CLAUDE.md "Sanitize user text fields on backend".
 *
 * <p>Strips HTML/script tags and neutralizes stray angle brackets so stored
 * content can never carry active markup, while keeping the text human-readable.
 */
public final class TextSanitizer {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private TextSanitizer() {
    }

    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String stripped = TAG.matcher(input).replaceAll("");
        // Neutralize any remaining lone angle brackets (e.g. "<b" without a close).
        stripped = stripped.replace("<", "").replace(">", "");
        return stripped.trim();
    }
}
