package kz.hrms.splitupauth.pricing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.util.Set;
import kz.hrms.splitupauth.entity.PriceSnapshotOutcome;
import kz.hrms.splitupauth.pricing.SafeOutboundUrlPolicy.UnsafeOutboundUrlException;
import org.junit.jupiter.api.Test;

class SafeOutboundUrlPolicyTest {

  @Test
  void httpsPublicHost_isAllowedAndSanitized() throws Exception {
    SafeOutboundUrlPolicy policy =
        policy(
            false,
            Set.of(443),
            "",
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

    SafeOutboundUrlPolicy.SafeUrl out = policy.validate("https://example.com/pricing?token=secret");

    assertEquals("example.com", out.host());
    assertEquals("https://example.com/pricing?[REDACTED]", out.sanitizedUrl());
  }

  @Test
  void httpIsBlockedUnlessDevFlagAllowsIt() {
    SafeOutboundUrlPolicy policy = policy(false, Set.of(443), "", host -> new InetAddress[0]);

    UnsafeOutboundUrlException ex =
        assertThrows(
            UnsafeOutboundUrlException.class, () -> policy.validate("http://example.com/"));

    assertEquals(PriceSnapshotOutcome.URL_BLOCKED, ex.getOutcome());
  }

  @Test
  void privateAndMixedDnsAnswersAreBlocked() throws Exception {
    SafeOutboundUrlPolicy policy =
        policy(
            false,
            Set.of(443),
            "",
            host ->
                new InetAddress[] {
                  InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")
                });

    UnsafeOutboundUrlException ex =
        assertThrows(
            UnsafeOutboundUrlException.class, () -> policy.validate("https://example.com/"));

    assertEquals(PriceSnapshotOutcome.DNS_BLOCKED, ex.getOutcome());
  }

  @Test
  void ipv6SpellingsOfPrivateIpv4TargetsAreBlocked() throws Exception {
    for (String answer :
        Set.of(
            "64:ff9b::a00:5", // NAT64 -> 10.0.0.5
            "2002:a9fe:a9fe::1", // 6to4 -> 169.254.169.254 (cloud metadata)
            "::7f00:1", // IPv4-compatible -> 127.0.0.1
            "::ffff:c0a8:101")) { // IPv4-mapped -> 192.168.1.1
      InetAddress resolved = InetAddress.getByName(answer);
      SafeOutboundUrlPolicy policy =
          policy(false, Set.of(443), "", host -> new InetAddress[] {resolved});
      UnsafeOutboundUrlException ex =
          assertThrows(
              UnsafeOutboundUrlException.class,
              () -> policy.validate("https://example.com/"),
              answer);
      assertEquals(PriceSnapshotOutcome.DNS_BLOCKED, ex.getOutcome(), answer);
    }

    InetAddress publicViaNat64 = InetAddress.getByName("64:ff9b::5db8:d822"); // 93.184.216.34
    assertDoesNotThrow(
        () ->
            policy(false, Set.of(443), "", host -> new InetAddress[] {publicViaNat64})
                .validate("https://example.com/"));
  }

  @Test
  void localAndMetadataAddressesAreBlocked() {
    for (String url :
        Set.of(
            "https://localhost/",
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://169.254.169.254/",
            "https://postgres/")) {
      SafeOutboundUrlPolicy policy = policy(true, Set.of(443), "", host -> new InetAddress[0]);
      assertThrows(UnsafeOutboundUrlException.class, () -> policy.validate(url), url);
    }
  }

  @Test
  void userInfoAndForbiddenPortAreBlocked() {
    SafeOutboundUrlPolicy policy =
        policy(
            false, Set.of(443), "", host -> new InetAddress[] {InetAddress.getLoopbackAddress()});

    assertThrows(
        UnsafeOutboundUrlException.class, () -> policy.validate("https://user:pass@example.com/"));
    assertThrows(
        UnsafeOutboundUrlException.class, () -> policy.validate("https://example.com:8443/"));
  }

  @Test
  void allowlistSupportsExactAndSubdomainRules() throws Exception {
    SafeOutboundUrlPolicy policy =
        policy(
            false,
            Set.of(443),
            "example.com,*.trusted.example",
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

    assertEquals("example.com", policy.validate("https://example.com/").host());
    assertEquals("api.trusted.example", policy.validate("https://api.trusted.example/").host());
    UnsafeOutboundUrlException ex =
        assertThrows(UnsafeOutboundUrlException.class, () -> policy.validate("https://evil.test/"));
    assertEquals(PriceSnapshotOutcome.URL_BLOCKED, ex.getOutcome());
  }

  private static SafeOutboundUrlPolicy policy(
      boolean allowHttp,
      Set<Integer> allowedPorts,
      String allowedHosts,
      SafeOutboundUrlPolicy.Resolver resolver) {
    return new SafeOutboundUrlPolicy(
        allowHttp, allowedPorts, SafeOutboundUrlPolicy.parseHosts(allowedHosts), resolver);
  }
}
