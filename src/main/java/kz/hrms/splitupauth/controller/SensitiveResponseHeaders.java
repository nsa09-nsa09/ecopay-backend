package kz.hrms.splitupauth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

final class SensitiveResponseHeaders {

  private SensitiveResponseHeaders() {}

  static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.EXPIRES, "0")
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .body(body);
  }
}
