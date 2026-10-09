package kz.hrms.splitupauth.payment.gateway.freedom;

import java.util.Map;

/**
 * A provider response whose signature has already been checked by {@link FreedomPayClient}.
 *
 * @param message every field as received (repeated and nested elements preserved)
 * @param fields first-value view of the top-level text fields for business code
 * @param signed true when {@code pg_sig} was present and valid; false only for an unsigned error
 *     response, which can never be interpreted as success
 */
public record FreedomPayResponse(
    FreedomPayMessage message, Map<String, String> fields, boolean signed) {

  public String get(String name) {
    return fields.get(name);
  }

  public String getOrDefault(String name, String fallback) {
    String value = fields.get(name);
    return value == null ? fallback : value;
  }

  public String status() {
    return getOrDefault("pg_status", "");
  }

  public boolean isOk() {
    return signed && "ok".equalsIgnoreCase(status());
  }
}
