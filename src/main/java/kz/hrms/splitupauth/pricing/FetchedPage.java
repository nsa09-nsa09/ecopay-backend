package kz.hrms.splitupauth.pricing;

import java.util.Map;

/**
 * Immutable snapshot of one HTTP fetch: the requested URL, the body, HTTP status and select
 * response headers (ETag / Last-Modified so callers can send conditional requests on the next
 * tick). Currency and locale are copied from the owning provider so extractors have a hint when
 * the page itself doesn't advertise one.
 */
public record FetchedPage(
    String url,
    int status,
    String body,
    Map<String, String> headers,
    String expectedCurrency,
    String locale) {

  public String header(String name) {
    if (headers == null || name == null) {
      return null;
    }
    for (Map.Entry<String, String> e : headers.entrySet()) {
      if (name.equalsIgnoreCase(e.getKey())) {
        return e.getValue();
      }
    }
    return null;
  }
}
