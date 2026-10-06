package kz.hrms.splitupauth.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Operational money metrics for Prometheus.
 *
 * <p>State gauges (payouts held/due/pending-provider/review, refunds pending/review, open intents,
 * webhook dead letters) are refreshed from cheap aggregate queries once a minute and served from
 * memory, so a scrape never hits the database. Event counters ({@link #paymentInit}, {@link
 * #reconciliation}) go through Micrometer's global registry, which Spring Boot binds to the
 * application registry. Labels are bounded enums only — never user, payment, room ids or tokens.
 */
@Component
@Slf4j
public class MoneyOperationsMetrics {

  private static final Map<String, String> GAUGES =
      Map.ofEntries(
          Map.entry(
              "payouts_held",
              "SELECT COUNT(*) FROM payouts WHERE status IN ('PENDING','PENDING_METHOD','FROZEN')"
                  + " AND release_at > ?"),
          Map.entry(
              "payouts_due",
              "SELECT COUNT(*) FROM payouts WHERE status IN ('PENDING','PENDING_METHOD')"
                  + " AND (release_at IS NULL OR release_at <= ?)"),
          Map.entry(
              "payouts_pending_provider",
              "SELECT COUNT(*) FROM payouts WHERE status = 'PENDING_PROVIDER'"),
          Map.entry(
              "payouts_requires_review",
              "SELECT COUNT(*) FROM payouts WHERE status = 'REQUIRES_REVIEW'"),
          Map.entry(
              "refunds_pending_provider",
              "SELECT COUNT(*) FROM refund_transactions WHERE status = 'PENDING_PROVIDER'"),
          Map.entry(
              "refunds_requires_review",
              "SELECT COUNT(*) FROM refund_transactions WHERE status = 'REQUIRES_REVIEW'"),
          Map.entry(
              "intents_open",
              "SELECT COUNT(*) FROM payment_intents WHERE status IN"
                  + " ('PENDING','UNKNOWN','RECONCILING')"),
          Map.entry(
              "intents_requires_review",
              "SELECT COUNT(*) FROM payment_intents WHERE status IN"
                  + " ('REQUIRES_REVIEW','CAPTURE_ANOMALY')"),
          Map.entry(
              "webhooks_dead_letter",
              "SELECT COUNT(*) FROM freedom_webhook_inbox WHERE processing_status = 'DEAD_LETTER'"));

  private final JdbcTemplate jdbcTemplate;
  private final Map<String, AtomicReference<Double>> values = new ConcurrentHashMap<>();
  private final AtomicReference<Double> heldAmount = new AtomicReference<>(Double.NaN);

  public MoneyOperationsMetrics(
      JdbcTemplate jdbcTemplate, ObjectProvider<MeterRegistry> meterRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    MeterRegistry registry = meterRegistry.getIfAvailable();
    for (String state : GAUGES.keySet()) {
      AtomicReference<Double> holder = new AtomicReference<>(Double.NaN);
      values.put(state, holder);
      if (registry != null) {
        Gauge.builder("ecopay.money.state", holder, AtomicReference::get)
            .tag("state", state)
            .description("Current count of money objects in the given state")
            .register(registry);
      }
    }
    if (registry != null) {
      Gauge.builder("ecopay.payout.held.amount.kzt", heldAmount, AtomicReference::get)
          .description("Owner payout reserve still inside its hold window, KZT")
          .register(registry);
    }
  }

  @Scheduled(
      fixedDelayString = "${app.metrics.money-refresh-ms:60000}",
      initialDelayString = "${app.metrics.money-initial-delay-ms:30000}")
  public void refresh() {
    LocalDateTime now = LocalDateTime.now();
    GAUGES.forEach(
        (state, sql) -> {
          try {
            Long count =
                sql.contains("?")
                    ? jdbcTemplate.queryForObject(sql, Long.class, now)
                    : jdbcTemplate.queryForObject(sql, Long.class);
            values.get(state).set(count == null ? Double.NaN : count.doubleValue());
          } catch (RuntimeException ex) {
            values.get(state).set(Double.NaN);
            log.debug("Metric {} refresh failed: {}", state, ex.getClass().getSimpleName());
          }
        });
    try {
      java.math.BigDecimal amount =
          jdbcTemplate.queryForObject(
              "SELECT COALESCE(SUM(COALESCE(payable_amount, amount)), 0) FROM payouts"
                  + " WHERE currency = 'KZT' AND status IN ('PENDING','PENDING_METHOD','FROZEN')"
                  + " AND release_at > ?",
              java.math.BigDecimal.class,
              now);
      heldAmount.set(amount == null ? Double.NaN : amount.doubleValue());
    } catch (RuntimeException ex) {
      heldAmount.set(Double.NaN);
    }
  }

  /** outcome: redirect | captured | accepted | failed | unknown | existing. */
  public static void paymentInit(String outcome) {
    Metrics.counter("ecopay.payment.init", "outcome", outcome).increment();
  }

  /** outcome: success | failed | review | pending | not_found | error. */
  public static void reconciliation(String source, String outcome) {
    Metrics.counter("ecopay.payment.reconciliation", "source", source, "outcome", outcome)
        .increment();
  }
}
