package kz.hrms.splitupauth.entity;

/** Outcome of a single fetch+extract attempt recorded in {@code price_snapshot}. */
public enum PriceSnapshotOutcome {
  SUCCESS,
  NOT_MODIFIED,
  PARSE_FAILED,
  FETCH_FAILED,
  DNS_BLOCKED,
  URL_BLOCKED,
  REDIRECT_BLOCKED,
  RESPONSE_TOO_LARGE,
  UNSUPPORTED_CONTENT_TYPE,
  DECOMPRESSION_FAILED,
  CURRENCY_MISMATCH,
  REQUIRES_JS,
  RATE_LIMITED,
  BLOCKED
}
