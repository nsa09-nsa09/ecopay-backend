package kz.hrms.splitupauth.entity;

/** Outcome of a single fetch+extract attempt recorded in {@code price_snapshot}. */
public enum PriceSnapshotOutcome {
  SUCCESS,
  PARSE_FAILED,
  FETCH_FAILED,
  BLOCKED
}
