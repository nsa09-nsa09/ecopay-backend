package kz.hrms.splitupauth.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Single source of the caller's network address for rate limiting and audit.
 *
 * <p>Raw {@code X-Forwarded-For} is never read here: its left-most entry is chosen by the client
 * and trivially spoofable, which would let one host rotate fake IPs past every per-IP limit. The
 * container's {@code RemoteIpValve} ({@code server.forward-headers-strategy=native}) already walks
 * the header from the right, accepts entries only from configured internal proxies and rewrites
 * {@link HttpServletRequest#getRemoteAddr()} to the first untrusted hop, so that value is the
 * trustworthy one.
 */
public final class ClientIp {

  private static final int MAX_LENGTH = 64;

  private ClientIp() {}

  public static String of(HttpServletRequest request) {
    if (request == null) {
      return "unknown";
    }
    String ip = request.getRemoteAddr();
    if (ip == null || ip.isBlank()) {
      return "unknown";
    }
    ip = ip.trim();
    return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
  }

  /** Address of the request bound to the current thread, or {@code "unknown"} outside a request. */
  public static String current() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servlet) {
      return of(servlet.getRequest());
    }
    return "unknown";
  }
}
