package kz.hrms.splitupauth.util;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Generates URL-safe slugs from human-readable names. Handles common
 * Cyrillic transliteration so the resulting slug stays readable for KZ/RU
 * inputs (e.g., "Видео" → "video", "Билайн" → "bilain"). Caller is
 * responsible for ensuring uniqueness — see {@link #appendSuffix}.
 */
public final class Slugifier {

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Map<Character, String> CYRILLIC = buildCyrillicMap();

    private Slugifier() {
    }

    public static String slugify(String input) {
        if (input == null) {
            return "";
        }
        String transliterated = transliterate(input.toLowerCase(Locale.ROOT));
        String normalized = Normalizer.normalize(transliterated, Normalizer.Form.NFKD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String slug = NON_SLUG.matcher(normalized).replaceAll("-");
        slug = EDGE_DASHES.matcher(slug).replaceAll("");
        return slug;
    }

    public static String appendSuffix(String slug, int suffix) {
        return slug + "-" + suffix;
    }

    private static String transliterate(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String mapped = CYRILLIC.get(c);
            if (mapped != null) {
                out.append(mapped);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Map<Character, String> buildCyrillicMap() {
        Map<Character, String> m = new HashMap<>();
        // Russian
        m.put('а', "a"); m.put('б', "b"); m.put('в', "v"); m.put('г', "g");
        m.put('д', "d"); m.put('е', "e"); m.put('ё', "yo"); m.put('ж', "zh");
        m.put('з', "z"); m.put('и', "i"); m.put('й', "i"); m.put('к', "k");
        m.put('л', "l"); m.put('м', "m"); m.put('н', "n"); m.put('о', "o");
        m.put('п', "p"); m.put('р', "r"); m.put('с', "s"); m.put('т', "t");
        m.put('у', "u"); m.put('ф', "f"); m.put('х', "kh"); m.put('ц', "ts");
        m.put('ч', "ch"); m.put('ш', "sh"); m.put('щ', "sch"); m.put('ъ', "");
        m.put('ы', "y"); m.put('ь', ""); m.put('э', "e"); m.put('ю', "yu");
        m.put('я', "ya");
        // Kazakh extras
        m.put('ә', "a"); m.put('і', "i"); m.put('ң', "ng"); m.put('ғ', "g");
        m.put('ү', "u"); m.put('ұ', "u"); m.put('қ', "q"); m.put('ө', "o");
        m.put('һ', "h");
        return m;
    }
}
