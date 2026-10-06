package kz.hrms.splitupauth.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.function.Supplier;
import kz.hrms.splitupauth.util.ClientIp;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Allows a request only from loopback / private-network peers (as resolved by the trusted-proxy
 * valve). Used for operational endpoints such as {@code /actuator/prometheus}: the in-cluster
 * scraper can read them, an internet client — whose address the valve resolves to a public IP —
 * cannot.
 */
public final class InternalNetworkAccess
    implements AuthorizationManager<RequestAuthorizationContext> {

  @Override
  public AuthorizationResult authorize(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
    return new AuthorizationDecision(isInternal(ClientIp.of(context.getRequest())));
  }

  static boolean isInternal(String ip) {
    // Literal addresses only (dotted IPv4 or colon IPv6): never trigger a DNS lookup.
    boolean ipv4Literal = ip != null && ip.matches("[0-9]{1,3}([.][0-9]{1,3}){3}");
    boolean ipv6Literal = ip != null && ip.contains(":") && ip.matches("[0-9a-fA-F:.]+");
    if (!ipv4Literal && !ipv6Literal) {
      return false;
    }
    try {
      InetAddress address = InetAddress.getByName(ip);
      if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
        return true;
      }
      byte[] b = address.getAddress();
      if (address instanceof Inet4Address) {
        return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10 (CGNAT)
      }
      if (address instanceof Inet6Address) {
        return (b[0] & 0xfe) == 0xfc; // fc00::/7 unique local
      }
      return false;
    } catch (Exception ex) {
      return false;
    }
  }
}
