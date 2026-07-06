package kz.hrms.splitupauth.pricing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import kz.hrms.splitupauth.entity.PriceWatchProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around {@link HttpClient} tuned for the Price Watch use case:
 *
 * <ul>
 *   <li>Explicit User-Agent (default: a real recent Chrome string) and {@code Accept-Encoding:
 *       gzip} so upstreams don't gate us as a bot on the User-Agent alone.
 *   <li>Configurable connect + read timeout; no follow-redirects to arbitrary hosts (only
 *       same-host or a well-known checkout host per provider) to avoid getting bounced onto an
 *       unrelated landing page or tracker.
 *   <li>Conditional GET when the caller supplies an ETag or Last-Modified from the previous
 *       observation — a 304 response is returned as a distinguished {@link FetchResult}.
 *   <li>{@code requiresJs=true} providers short-circuit to {@link FetchResult#blocked}: v1 does
 *       not run a headless browser. The extension point for a Playwright sidecar in v2 lives
 *       here (see {@link #fetch}).
 * </ul>
 */
@Slf4j
@Component
public class PageFetcher {

  @Value("${app.pricing.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36}")
  private String userAgent;

  @Value("${app.pricing.timeout-seconds:8}")
  private long timeoutSeconds;

  /**
   * Perform one fetch. {@code etag} / {@code lastModified} may come from the last snapshot to
   * make the request conditional; either may be {@code null}.
   */
  public FetchResult fetch(PriceWatchProvider provider, String etag, String lastModified) {
    if (Boolean.TRUE.equals(provider.getRequiresJs())) {
      // v2 hook: swap this branch for a Playwright/headless call. Until then, we
      // record a BLOCKED snapshot so the admin sees the row and can drop in a
      // MANUAL price without the scheduler eating retries.
      return FetchResult.blocked(null,
          "requires_js=true; headless renderer not enabled (v2)");
    }

    URI uri;
    try {
      uri = URI.create(provider.getUrl());
    } catch (Exception ex) {
      return FetchResult.fetchFailed(null, "invalid url: " + ex.getMessage());
    }
    String targetHost = uri.getHost();
    if (targetHost == null) {
      return FetchResult.fetchFailed(null, "url has no host");
    }

    try {
      HttpClient client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(timeoutSeconds))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();

      HttpRequest.Builder rq =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("User-Agent", userAgent)
              .header("Accept",
                  "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
              .header("Accept-Language",
                  Optional.ofNullable(provider.getLocale()).orElse("en"))
              .header("Accept-Encoding", "gzip")
              .GET();

      if (etag != null && !etag.isBlank()) {
        rq.header("If-None-Match", etag);
      }
      if (lastModified != null && !lastModified.isBlank()) {
        rq.header("If-Modified-Since", lastModified);
      }

      HttpResponse<byte[]> response = client.send(rq.build(),
          HttpResponse.BodyHandlers.ofByteArray());

      int status = response.statusCode();
      String respEtag = firstHeader(response, "ETag");
      String respLastMod = firstHeader(response, "Last-Modified");

      if (status == 304) {
        return FetchResult.notModified(status, respEtag, respLastMod);
      }

      // We do not chase redirects: some providers redirect to a locale-router or a
      // consent gate that yields a body without any price. Follow only if it lands
      // on the same host — otherwise record it as blocked so the admin re-points.
      if (status >= 300 && status < 400) {
        String location = firstHeader(response, "Location");
        if (location == null) {
          return FetchResult.blocked(status, "redirect without Location header");
        }
        URI next;
        try {
          next = uri.resolve(location);
        } catch (Exception ex) {
          return FetchResult.blocked(status, "unparseable Location: " + location);
        }
        if (next.getHost() == null || !next.getHost().equalsIgnoreCase(targetHost)) {
          return FetchResult.blocked(status, "cross-host redirect to " + next.getHost());
        }
        // Same-host redirect: one manual hop, no more.
        HttpRequest.Builder hop =
            HttpRequest.newBuilder(next)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language",
                    Optional.ofNullable(provider.getLocale()).orElse("en"))
                .header("Accept-Encoding", "gzip")
                .GET();
        response = client.send(hop.build(), HttpResponse.BodyHandlers.ofByteArray());
        status = response.statusCode();
        respEtag = firstHeader(response, "ETag");
        respLastMod = firstHeader(response, "Last-Modified");
      }

      if (status == 403 || status == 429 || status == 451) {
        return FetchResult.blocked(status, "http " + status);
      }
      if (status < 200 || status >= 300) {
        return FetchResult.fetchFailed(status, "http " + status);
      }

      byte[] rawBytes = response.body();
      boolean gzipped = "gzip".equalsIgnoreCase(firstHeader(response, "Content-Encoding"));
      String body = decode(rawBytes, gzipped, firstHeader(response, "Content-Type"));

      Map<String, String> headers = new LinkedHashMap<>();
      response.headers().map().forEach((k, v) -> {
        if (v != null && !v.isEmpty()) headers.put(k, v.get(0));
      });

      FetchedPage page = new FetchedPage(uri.toString(), status, body, headers,
          provider.getExpectedCurrency(), provider.getLocale());
      return FetchResult.ok(page, respEtag, respLastMod);
    } catch (Exception ex) {
      log.debug("Price fetch failed for provider {} ({}): {}",
          provider.getId(), provider.getUrl(), ex.getMessage());
      return FetchResult.fetchFailed(null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }
  }

  private static String firstHeader(HttpResponse<?> resp, String name) {
    Map<String, List<String>> map = resp.headers().map();
    for (Map.Entry<String, List<String>> e : map.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
        return e.getValue().get(0);
      }
    }
    return null;
  }

  private static String decode(byte[] bytes, boolean gzipped, String contentType) {
    if (bytes == null || bytes.length == 0) {
      return "";
    }
    byte[] plain = bytes;
    if (gzipped) {
      try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = gz.read(buf)) != -1) {
          out.write(buf, 0, n);
        }
        plain = out.toByteArray();
      } catch (Exception ex) {
        // Fall back to raw bytes — a mislabelled Content-Encoding is a common
        // gotcha and better than throwing a whole snapshot away.
      }
    }
    Charset cs = charsetFrom(contentType);
    return new String(plain, cs);
  }

  private static Charset charsetFrom(String contentType) {
    if (contentType == null) return StandardCharsets.UTF_8;
    int idx = contentType.toLowerCase().indexOf("charset=");
    if (idx < 0) return StandardCharsets.UTF_8;
    String name = contentType.substring(idx + "charset=".length()).trim();
    int semi = name.indexOf(';');
    if (semi >= 0) name = name.substring(0, semi).trim();
    // strip quotes if any
    if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
      name = name.substring(1, name.length() - 1);
    }
    try {
      return Charset.forName(name);
    } catch (Exception ex) {
      return StandardCharsets.UTF_8;
    }
  }

  /** Test/scheduler seam: fixed headers, no network, for unit tests to exercise extractors. */
  public static Map<String, String> emptyHeaders() {
    return new HashMap<>();
  }
}
