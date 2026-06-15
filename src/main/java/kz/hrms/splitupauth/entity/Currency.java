package kz.hrms.splitupauth.entity;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whitelist of currencies a room owner may price in. KZT is the platform's
 * settlement currency; everything else is stored alongside a KZT snapshot
 * (see V27) so we can aggregate revenue without round-tripping FX rates.
 */
public enum Currency {
    KZT,
    UZS,
    KGS,
    USD,
    EUR,
    CNY,
    GBP,
    RUB;

    private static final Set<String> CODES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isSupported(String code) {
        return code != null && CODES.contains(code.toUpperCase());
    }

    public static Currency normalize(String code) {
        if (code == null || code.isBlank()) {
            return KZT;
        }
        return Currency.valueOf(code.toUpperCase());
    }
}
