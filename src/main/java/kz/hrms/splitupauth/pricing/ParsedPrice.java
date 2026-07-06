package kz.hrms.splitupauth.pricing;

import java.math.BigDecimal;

/** Single extracted price. Currency is uppercase ISO-4217 when known, else {@code null}. */
public record ParsedPrice(BigDecimal price, String currency, String source) {}
