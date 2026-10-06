package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real JNDI DNS path against the public internet.
 *
 * <p>Tagged {@code live-dns} and excluded from the normal build: it needs outbound DNS and its
 * results depend on records we do not control. Run it deliberately when touching {@link
 * EmailDomainService}:
 *
 * <pre>./mvnw test -Dtest=EmailDomainServiceLiveDnsTest -Dgroups=live-dns</pre>
 */
@Tag("live-dns")
class EmailDomainServiceLiveDnsTest {

  /** Same knobs as production defaults. */
  private EmailDomainService service() {
    return new EmailDomainService(true, 2500, 1440, 10000);
  }

  @Test
  void resolvesRealDomainThatAcceptsMail() {
    // Not in the well-known fast-path list, so this genuinely hits DNS.
    assertEquals(EmailDomainService.DomainStatus.HAS_MX, service().resolve("microsoft.com"));
  }

  @Test
  void rejectsDomainThatDoesNotExist() {
    // .invalid is reserved by RFC 2606 and guaranteed never to resolve.
    assertEquals(
        EmailDomainService.DomainStatus.NO_MX, service().resolve("no-such-domain-xyz123.invalid"));
  }

  @Test
  void wellKnownDomainsShortCircuitWithoutDns() {
    // Should be instant: the fast path must not touch the network at all.
    long start = System.nanoTime();
    assertEquals(EmailDomainService.DomainStatus.HAS_MX, service().resolve("gmail.com"));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertTrue(elapsedMs < 50, "well-known domain took " + elapsedMs + "ms — it hit the network");
  }

  @Test
  void cachesDecisiveAnswers() {
    EmailDomainService service = service();

    long firstStart = System.nanoTime();
    EmailDomainService.DomainStatus first = service.resolve("wikipedia.org");
    long firstMs = (System.nanoTime() - firstStart) / 1_000_000;

    long secondStart = System.nanoTime();
    EmailDomainService.DomainStatus second = service.resolve("wikipedia.org");
    long secondMs = (System.nanoTime() - secondStart) / 1_000_000;

    assertEquals(first, second);
    assertNotEquals(EmailDomainService.DomainStatus.UNVERIFIABLE, first, "expected a real answer");
    assertTrue(
        secondMs <= 5,
        "second lookup took " + secondMs + "ms (first " + firstMs + "ms) — not cached");
  }

  @Test
  void aggressiveTimeoutDegradesToUnverifiable_ratherThanHanging() {
    // 1ms budget guarantees the lookup cannot finish. The contract is that we
    // give up fast and report UNVERIFIABLE (which callers let through), never
    // that we block the request thread.
    EmailDomainService impatient = new EmailDomainService(true, 1, 1440, 10000);

    long start = System.nanoTime();
    EmailDomainService.DomainStatus status = impatient.resolve("wikipedia.org");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertEquals(EmailDomainService.DomainStatus.UNVERIFIABLE, status);
    assertTrue(elapsedMs < 1000, "timeout was not honoured: took " + elapsedMs + "ms");
  }

  @Test
  void disabledServiceNeverTouchesDns() {
    EmailDomainService disabled = new EmailDomainService(false, 2500, 1440, 10000);
    assertEquals(EmailDomainService.DomainStatus.UNVERIFIABLE, disabled.resolve("microsoft.com"));
  }
}
