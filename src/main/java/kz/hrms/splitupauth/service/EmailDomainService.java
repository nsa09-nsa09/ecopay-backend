package kz.hrms.splitupauth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Answers "can this domain receive mail at all?" by looking for an MX record (falling back to A, as
 * RFC 5321 §5.1 allows an implicit MX). A domain with neither cannot accept mail, so sending to it
 * is guaranteed to bounce — rejecting up front keeps typo'd and dead addresses out of the database
 * and saves the send quota.
 *
 * <p>Deliberately NOT an SMTP RCPT-TO probe: most providers rate-limit or blacklist probing hosts
 * and answer with catch-all "250 OK" anyway, so the results would be both risky and wrong. The only
 * real proof an address exists is the confirmation email.
 *
 * <p>Results are cached per domain so a login storm doesn't hammer DNS, and each lookup runs on a
 * separate thread with a hard timeout — JNDI's own timeouts are advisory and a hung resolver would
 * otherwise stall the request thread.
 */
@Service
@Slf4j
public class EmailDomainService {

  /** Outcome of a lookup. {@code UNVERIFIABLE} means DNS failed us, not the user. */
  public enum DomainStatus {
    HAS_MX,
    NO_MX,
    UNVERIFIABLE
  }

  private final Cache<String, DomainStatus> cache;
  private final ExecutorService lookupExecutor;
  private final boolean enabled;
  private final long timeoutMillis;

  public EmailDomainService(
      @Value("${app.email.mx-check.enabled:true}") boolean enabled,
      @Value("${app.email.mx-check.timeout-ms:2500}") long timeoutMillis,
      @Value("${app.email.mx-check.cache-ttl-minutes:1440}") long cacheTtlMinutes,
      @Value("${app.email.mx-check.cache-max-size:10000}") long cacheMaxSize) {
    this.enabled = enabled;
    this.timeoutMillis = timeoutMillis;
    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes))
            .maximumSize(cacheMaxSize)
            .build();
    this.lookupExecutor =
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "mx-lookup");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * @param domain already-normalized (lowercase, no whitespace) domain part
   * @return whether the domain can receive mail; never throws
   */
  public DomainStatus resolve(String domain) {
    if (!enabled || domain == null || domain.isBlank()) {
      return DomainStatus.UNVERIFIABLE;
    }
    // The big providers are not worth a DNS round-trip on every login attempt.
    if (EmailNormalizer.isWellKnownDomain(domain)) {
      return DomainStatus.HAS_MX;
    }

    DomainStatus cached = cache.getIfPresent(domain);
    if (cached != null) {
      return cached;
    }

    DomainStatus status = lookupWithTimeout(domain);

    // Only cache decisive answers. Caching UNVERIFIABLE for a day would turn a
    // transient DNS blip into a day-long outage for that domain.
    if (status != DomainStatus.UNVERIFIABLE) {
      cache.put(domain, status);
    }
    return status;
  }

  private DomainStatus lookupWithTimeout(String domain) {
    Future<DomainStatus> future = lookupExecutor.submit(() -> lookup(domain));
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn(
          "MX lookup for '{}' timed out after {}ms; treating as unverifiable",
          domain,
          timeoutMillis);
      return DomainStatus.UNVERIFIABLE;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return DomainStatus.UNVERIFIABLE;
    } catch (Exception e) {
      log.warn("MX lookup for '{}' failed: {}", domain, e.getMessage());
      return DomainStatus.UNVERIFIABLE;
    }
  }

  private DomainStatus lookup(String domain) {
    Hashtable<String, String> env = new Hashtable<>();
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
    // Advisory — the hard guarantee is the Future timeout in lookupWithTimeout.
    env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeoutMillis));
    env.put("com.sun.jndi.dns.timeout.retries", "1");

    InitialDirContext ctx = null;
    try {
      ctx = new InitialDirContext(env);
      Attributes attributes = ctx.getAttributes(domain, new String[] {"MX", "A"});

      Attribute mx = attributes.get("MX");
      if (mx != null && mx.size() > 0) {
        return DomainStatus.HAS_MX;
      }
      // RFC 5321: a domain with an A record but no MX is still a valid mail
      // destination — the A record acts as an implicit MX.
      Attribute a = attributes.get("A");
      if (a != null && a.size() > 0) {
        return DomainStatus.HAS_MX;
      }
      return DomainStatus.NO_MX;
    } catch (NamingException e) {
      // NXDOMAIN and "no such attribute" both land here. We cannot tell them
      // apart portably, so treat a clean DNS answer with no records as NO_MX
      // only when the message says the name does not exist.
      String message = String.valueOf(e.getMessage());
      if (message.contains("DNS name not found") || message.contains("NXDOMAIN")) {
        return DomainStatus.NO_MX;
      }
      log.warn("MX lookup for '{}' returned {}", domain, e.getClass().getSimpleName());
      return DomainStatus.UNVERIFIABLE;
    } finally {
      if (ctx != null) {
        try {
          ctx.close();
        } catch (NamingException ignored) {
          // Nothing useful to do; the context is being discarded anyway.
        }
      }
    }
  }
}
