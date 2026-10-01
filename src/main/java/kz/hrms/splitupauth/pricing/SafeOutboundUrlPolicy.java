package kz.hrms.splitupauth.pricing;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SafeOutboundUrlPolicy {

  private final boolean allowHttp;
  private final Set<Integer> allowedPorts;
  private final Set<String> allowedHosts;
  private final Resolver resolver;

  @Autowired
  public SafeOutboundUrlPolicy(
      @Value("${app.pricing.allow-http:false}") boolean allowHttp,
      @Value("${app.pricing.allowed-ports:443}") String allowedPorts,
      @Value("${app.pricing.allowed-hosts:}") String allowedHosts) {
    this(allowHttp, parsePorts(allowedPorts), parseHosts(allowedHosts), InetAddress::getAllByName);
  }

  SafeOutboundUrlPolicy(
      boolean allowHttp, Set<Integer> allowedPorts, Set<String> allowedHosts, Resolver resolver) {
    this.allowHttp = allowHttp;
    this.allowedPorts = allowedPorts.isEmpty() ? Set.of(443) : Set.copyOf(allowedPorts);
    this.allowedHosts = Set.copyOf(allowedHosts);
    this.resolver = resolver;
  }

  public SafeUrl validate(String rawUrl) {
    URI uri = parse(rawUrl);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !("http".equals(scheme) && allowHttp)) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "Only HTTPS URLs are allowed");
    }
    if (uri.getRawUserInfo() != null || rawUrl.contains("@")) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "URL credentials are not allowed");
    }
    if (uri.getRawFragment() != null) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "URL fragments are not allowed");
    }
    String host = normalizeHost(uri.getHost());
    if (host == null) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "URL host is required");
    }
    int port = uri.getPort() >= 0 ? uri.getPort() : ("http".equals(scheme) ? 80 : 443);
    if (!allowedPorts.contains(port)) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "URL port is not allowed");
    }
    validateHostName(host);
    validateAllowedHost(host);
    List<InetAddress> addresses = resolve(host);
    if (addresses.isEmpty()) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.DNS_BLOCKED, "URL host did not resolve");
    }
    for (InetAddress address : addresses) {
      if (isBlockedAddress(address)) {
        throw new UnsafeOutboundUrlException(
            PriceSnapshotOutcome.DNS_BLOCKED, "URL host resolves to a blocked address range");
      }
    }
    return new SafeUrl(uri, scheme, host, port, List.copyOf(addresses), sanitize(uri, host, port));
  }

  public SafeUrl validateRedirect(
      SafeUrl previous, String location, Set<String> redirectAllowlist) {
    URI next;
    try {
      next = previous.uri().resolve(location);
    } catch (Exception ex) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.REDIRECT_BLOCKED, "Redirect Location is invalid");
    }
    SafeUrl safe = validate(next.toString());
    if ("https".equals(previous.scheme()) && "http".equals(safe.scheme())) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.REDIRECT_BLOCKED, "HTTPS to HTTP redirect is blocked");
    }
    if (!safe.host().equals(previous.host())) {
      Set<String> allowlist = redirectAllowlist == null ? Set.of() : redirectAllowlist;
      boolean allowed =
          hostAllowedBy(safe.host(), allowlist) || hostAllowedBy(safe.host(), allowedHosts);
      if (!allowed) {
        throw new UnsafeOutboundUrlException(
            PriceSnapshotOutcome.REDIRECT_BLOCKED, "Cross-host redirect is not allowlisted");
      }
    }
    return safe;
  }

  public String sanitize(String rawUrl) {
    try {
      URI uri = parse(rawUrl);
      String host = normalizeHost(uri.getHost());
      if (host == null) return "[invalid-url]";
      int port = uri.getPort() >= 0 ? uri.getPort() : -1;
      return sanitize(uri, host, port);
    } catch (Exception ex) {
      return "[invalid-url]";
    }
  }

  private URI parse(String rawUrl) {
    if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 2000) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "URL is required and must be under 2000 characters");
    }
    try {
      return new URI(rawUrl.trim()).normalize();
    } catch (URISyntaxException ex) {
      throw new UnsafeOutboundUrlException(PriceSnapshotOutcome.URL_BLOCKED, "URL is malformed");
    }
  }

  private static String sanitize(URI uri, String host, int port) {
    StringBuilder out = new StringBuilder();
    out.append(uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT));
    out.append("://").append(host);
    if (port > 0 && port != 443 && port != 80) {
      out.append(':').append(port);
    }
    String path = Optional.ofNullable(uri.getRawPath()).orElse("/");
    out.append(path.isBlank() ? "/" : path);
    if (uri.getRawQuery() != null) {
      out.append("?[REDACTED]");
    }
    return out.toString();
  }

  private static String normalizeHost(String host) {
    if (host == null || host.isBlank()) return null;
    String h = host;
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    return IDN.toASCII(h.trim().toLowerCase(Locale.ROOT));
  }

  private void validateHostName(String host) {
    if ("localhost".equals(host) || host.endsWith(".localhost")) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "localhost is not allowed");
    }
    if (!host.contains(".") && !isIpLiteral(host)) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "single-label hosts are not allowed");
    }
    if (host.endsWith(".local")
        || host.endsWith(".internal")
        || host.endsWith(".svc")
        || host.endsWith(".cluster.local")) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "internal service names are not allowed");
    }
  }

  private void validateAllowedHost(String host) {
    if (!allowedHosts.isEmpty() && !hostAllowedBy(host, allowedHosts)) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.URL_BLOCKED, "URL host is not in the Price Watch allowlist");
    }
  }

  private static boolean hostAllowedBy(String host, Set<String> allowlist) {
    for (String rule : allowlist) {
      if (rule.startsWith("*.")) {
        String suffix = rule.substring(1);
        if (host.endsWith(suffix) && host.length() > suffix.length()) return true;
      } else if (host.equals(rule)) {
        return true;
      }
    }
    return false;
  }

  private List<InetAddress> resolve(String host) {
    if (isIpLiteral(host)) {
      try {
        return List.of(InetAddress.getByName(host));
      } catch (UnknownHostException ex) {
        throw new UnsafeOutboundUrlException(
            PriceSnapshotOutcome.DNS_BLOCKED, "IP literal is invalid");
      }
    }
    try {
      return Arrays.asList(resolver.resolve(host));
    } catch (Exception ex) {
      throw new UnsafeOutboundUrlException(
          PriceSnapshotOutcome.DNS_BLOCKED, "DNS resolution failed");
    }
  }

  private static boolean isIpLiteral(String host) {
    return host.indexOf(':') >= 0 || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
  }

  private static boolean isBlockedAddress(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int a = bytes[0] & 0xff;
      int b = bytes[1] & 0xff;
      return a == 0
          || a == 10
          || a == 127
          || (a == 100 && b >= 64 && b <= 127)
          || (a == 169 && b == 254)
          || (a == 172 && b >= 16 && b <= 31)
          || (a == 192 && b == 0)
          || (a == 192 && b == 168)
          || (a == 198 && (b == 18 || b == 19))
          || a >= 224;
    }
    if (address instanceof Inet6Address) {
      if (bytes.length == 16) {
        boolean ipv4Mapped = true;
        for (int i = 0; i < 10; i++) ipv4Mapped &= bytes[i] == 0;
        ipv4Mapped &= (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
        if (ipv4Mapped) {
          byte[] v4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
          try {
            return isBlockedAddress(InetAddress.getByAddress(v4));
          } catch (UnknownHostException ignored) {
            return true;
          }
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return (first & 0xfe) == 0xfc || (first == 0x20 && second == 0x01);
      }
    }
    return false;
  }

  private static Set<Integer> parsePorts(String raw) {
    Set<Integer> out = new LinkedHashSet<>();
    for (String token : split(raw)) {
      try {
        int port = Integer.parseInt(token);
        if (port > 0 && port <= 65535) out.add(port);
      } catch (NumberFormatException ignored) {
      }
    }
    return out;
  }

  static Set<String> parseHosts(String raw) {
    Set<String> out = new LinkedHashSet<>();
    for (String token : split(raw)) {
      String h =
          token.startsWith("*.") ? "*." + normalizeHost(token.substring(2)) : normalizeHost(token);
      if (h != null && !h.isBlank()) out.add(h);
    }
    return out;
  }

  private static List<String> split(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String token : raw.split(",")) {
      String trimmed = token.trim();
      if (!trimmed.isBlank()) out.add(trimmed);
    }
    return out;
  }

  @FunctionalInterface
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  public record SafeUrl(
      URI uri,
      String scheme,
      String host,
      int port,
      List<InetAddress> addresses,
      String sanitizedUrl) {}

  @Getter
  public static class UnsafeOutboundUrlException extends RuntimeException {
    private final PriceSnapshotOutcome outcome;

    UnsafeOutboundUrlException(PriceSnapshotOutcome outcome, String message) {
      super(message);
      this.outcome = outcome;
    }
  }
}
