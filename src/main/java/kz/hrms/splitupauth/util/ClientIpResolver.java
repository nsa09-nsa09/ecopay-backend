package kz.hrms.splitupauth.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Single source of truth for the client IP used by rate limiting / anti-abuse.
 *
 * <p>The app runs with {@code server.forward-headers-strategy=framework}, so Spring's {@code
 * ForwardedHeaderFilter} has already rewritten {@link HttpServletRequest#getRemoteAddr()} to the
 * client address taken from the {@code X-Forwarded-*} chain set by the reverse proxy. We therefore
 * read {@code getRemoteAddr()} rather than parsing {@code X-Forwarded-For} by hand (whose left-most
 * token is attacker-controlled).
 *
 * <p><b>INFRA REQUIREMENT:</b> the framework strategy trusts the forwarded headers unconditionally.
 * Every IP-based limit here is only as trustworthy as the guarantee that the backend is reachable
 * <i>exclusively</i> through nginx, and that nginx overwrites (not appends) {@code X-Forwarded-For}
 * with the real peer. If the container port is reachable directly, a client can spoof the header and
 * bypass every per-IP limit. That isolation must be enforced at the network/ingress layer.
 */
public final class ClientIpResolver {

  private ClientIpResolver() {}

  /** Returns the resolved client IP, or {@code null} when unavailable (e.g. no request bound). */
  public static String resolve(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String ip = request.getRemoteAddr();
    return (ip == null || ip.isBlank()) ? null : ip;
  }
}
