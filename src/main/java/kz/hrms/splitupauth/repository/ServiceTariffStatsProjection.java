package kz.hrms.splitupauth.repository;

import java.math.BigDecimal;

/**
 * Per-service tariff aggregates used by the public catalog: min per-member
 * price (basePriceTotal / maxMembers across active tariffs), the currency of
 * the cheapest tariff, and the active tariff count. Returned by
 * {@link TariffPlanRepository#findStatsByServiceIds}.
 */
public interface ServiceTariffStatsProjection {
    Long getServiceId();

    BigDecimal getMinPricePerMember();

    String getCheapestCurrency();

    Long getTariffCount();
}
