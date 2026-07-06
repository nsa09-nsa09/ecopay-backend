package kz.hrms.splitupauth.entity;

/** Health status of a {@link PriceWatchProvider} as observed by the scheduler. */
public enum PriceWatchStatus {
  OK,
  STALE,
  FAILING,
  BLOCKED,
  PENDING
}
