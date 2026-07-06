package kz.hrms.splitupauth.payment.gateway.freedom;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Resolves the absolute URLs that are handed to Freedom Pay (webhook result URLs and browser
 * success/failure redirects).
 *
 * <p>The configured values (see {@code application.properties}) default to {@code localhost} so
 * local dev works out of the box, but those defaults must never leak into a deployed environment.
 * This resolver derives the real host from the current HTTP request — the same approach {@code
 * AvatarStorageService} uses for avatar links — and only falls back to the configured value when it
 * is an explicit, non-loopback URL (i.e. an operator deliberately set {@code FREEDOMPAY_*_URL}).
 * The reverse proxy's {@code X-Forwarded-*} headers are honoured via {@code
 * server.forward-headers-strategy=framework}, so behind nginx this yields the public https host
 * automatically.
 *
 * <p>Resolution priority:
 *
 * <ol>
 *   <li>an explicit, non-loopback configured/override URL (deliberate ops setting);
 *   <li>otherwise, a URL built from the current request host;
 *   <li>as a last resort (no request in scope), the configured value as-is.
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreedomPayUrlResolver {

  /** Backend webhook paths — must match {@code FreedomPayWebhookController}'s mappings. */
  public static final String RESULT_PATH = "/api/v1/webhooks/freedompay/result";

  public static final String PAYOUT_RESULT_PATH = "/api/v1/webhooks/freedompay/payout-result";

  /** Frontend SPA redirect routes. */
  public static final String SUCCESS_PATH = "/payment/confirmation";

  public static final String FAILURE_PATH = "/payment/failure";

  private final FreedomPayProperties properties;

  /** Charge webhook URL Freedom Pay POSTs to — points at THIS backend. */
  public String resultUrl() {
    return resolveBackend(properties.getResultUrl(), RESULT_PATH);
  }

  /** Payout webhook URL Freedom Pay POSTs to — points at THIS backend. */
  public String payoutResultUrl() {
    return resolveBackend(properties.getPayoutResultUrl(), PAYOUT_RESULT_PATH);
  }

  /** Browser redirect after a successful payment — points at the frontend SPA. */
  public String successUrl(String perRequestOverride) {
    return resolveFrontend(perRequestOverride, properties.getSuccessUrl(), SUCCESS_PATH);
  }

  /** Browser redirect after a failed payment — points at the frontend SPA. */
  public String failureUrl(String perRequestOverride) {
    return resolveFrontend(perRequestOverride, properties.getFailureUrl(), FAILURE_PATH);
  }

  private String resolveBackend(String configured, String path) {
    if (isUsable(configured)) {
      return configured;
    }
    String base = currentBackendBase();
    if (base != null) {
      return base + path;
    }
    log.warn(
        "No request in scope and no explicit Freedom Pay URL configured; "
            + "falling back to '{}' (set FREEDOMPAY_*_URL in deployed environments)",
        configured);
    return configured == null ? "" : configured;
  }

  private String resolveFrontend(String override, String configured, String path) {
    // An explicit per-request return URL is the caller's actual SPA origin (e.g. the
    // payout-card binding's /payment/card-connected page). Honour it verbatim even on
    // localhost — the loopback filter only guards the *configured* fallback defaults, which
    // could otherwise leak localhost into a deployed environment.
    if (override != null && !override.isBlank()) {
      return override;
    }
    if (isUsable(configured)) {
      return configured;
    }
    String origin = currentFrontendOrigin();
    if (origin != null) {
      return stripTrailingSlashes(origin) + path;
    }
    return configured == null ? "" : configured;
  }

  /** A configured URL is "usable" only if present and not a loopback host. */
  private static boolean isUsable(String url) {
    return url != null && !url.isBlank() && !isLoopback(url);
  }

  private static boolean isLoopback(String url) {
    try {
      String host = URI.create(url.trim()).getHost();
      if (host == null) {
        return true; // unparseable / relative — treat as not usable
      }
      host = host.toLowerCase();
      return host.equals("localhost")
          || host.equals("127.0.0.1")
          || host.equals("0.0.0.0")
          || host.equals("::1")
          || host.endsWith(".localhost");
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /** Scheme+host(+port) of the current request — the backend's public base URL. */
  private static String currentBackendBase() {
    if (RequestContextHolder.getRequestAttributes() == null) {
      return null;
    }
    try {
      return stripTrailingSlashes(
          ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
    } catch (IllegalStateException e) {
      return null;
    }
  }

  /**
   * Origin of the SPA that initiated the payment. Prefers the {@code Origin} header, then {@code
   * Referer}; both reflect where the user's browser actually is, so the redirect lands back on the
   * same frontend (localhost in dev, the public domain in prod).
   */
  private static String currentFrontendOrigin() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return null;
    }
    HttpServletRequest req = attrs.getRequest();

    String origin = req.getHeader("Origin");
    if (origin != null && !origin.isBlank()) {
      return origin.trim();
    }

    String referer = req.getHeader("Referer");
    if (referer != null && !referer.isBlank()) {
      try {
        URI u = URI.create(referer.trim());
        if (u.getScheme() != null && u.getHost() != null) {
          String base = u.getScheme() + "://" + u.getHost();
          if (u.getPort() > 0) {
            base += ":" + u.getPort();
          }
          return base;
        }
      } catch (IllegalArgumentException ignored) {
        // malformed Referer — fall through
      }
    }
    return null;
  }

  private static String stripTrailingSlashes(String s) {
    return s == null ? null : s.replaceAll("/+$", "");
  }
}
