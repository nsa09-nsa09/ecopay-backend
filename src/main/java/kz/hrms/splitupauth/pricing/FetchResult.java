package kz.hrms.splitupauth.pricing;

import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;

/**
 * Union-style result of a fetch attempt. Success carries a {@link FetchedPage}; failure carries an
 * outcome (network vs blocked vs not-modified) and a short error message. Not-modified (HTTP 304)
 * is modeled as a success with a {@code null} page and outcome {@link PriceSnapshotOutcome#SUCCESS}
 * — the caller keeps the previous price and simply resets scheduling.
 */
public record FetchResult(
    FetchedPage page,
    PriceSnapshotOutcome outcome,
    Integer httpStatus,
    String errorMessage,
    String etag,
    String lastModified,
    boolean notModified) {

  public static FetchResult ok(FetchedPage page, String etag, String lastModified) {
    return new FetchResult(
        page, PriceSnapshotOutcome.SUCCESS, page.status(), null, etag, lastModified, false);
  }

  public static FetchResult notModified(int status, String etag, String lastModified) {
    return new FetchResult(
        null, PriceSnapshotOutcome.SUCCESS, status, null, etag, lastModified, true);
  }

  public static FetchResult fetchFailed(Integer status, String message) {
    return new FetchResult(
        null, PriceSnapshotOutcome.FETCH_FAILED, status, message, null, null, false);
  }

  public static FetchResult blocked(Integer status, String message) {
    return new FetchResult(null, PriceSnapshotOutcome.BLOCKED, status, message, null, null, false);
  }
}
