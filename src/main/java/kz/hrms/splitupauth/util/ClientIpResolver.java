package kz.hrms.splitupauth.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client IP for rate limiting / anti-abuse, as a nullable value. Delegates to {@link ClientIp}, the
 * single source of truth: with {@code server.forward-headers-strategy=native} Tomcat's
 * RemoteIpValve rewrites {@link HttpServletRequest#getRemoteAddr()} from {@code X-Forwarded-For}
 * ONLY when the peer matches the trusted reverse-proxy range ({@code
 * server.tomcat.remoteip.internal-proxies}), so a header sent by an internet client is ignored.
 */
public final class ClientIpResolver {

  private ClientIpResolver() {}

  /**
   * Returns the resolved client IP, or {@code null} when unavailable (no request given). Every web
   * entry point passes its request explicitly; internal callers without one get no IP bucket.
   */
  public static String resolve(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String ip = ClientIp.of(request);
    return (ip == null || ip.isBlank() || "unknown".equals(ip)) ? null : ip;
  }
}
