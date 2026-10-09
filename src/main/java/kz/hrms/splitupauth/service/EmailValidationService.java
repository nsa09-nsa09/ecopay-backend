package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.exception.InvalidEmailException;
import kz.hrms.splitupauth.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Single entry point for the pre-delivery email checks, applied in increasing order of strictness:
 *
 * <ol>
 *   <li><b>Format</b> — cheap, synchronous, always on. Also the only level applied on lookup paths
 *       (login, password reset) where the address is already in the database.
 *   <li><b>Domain</b> — MX lookup via {@link EmailDomainService}. Applied only where a new address
 *       is being attached to an account, so we never spend a confirmation email on a domain that
 *       cannot receive mail.
 *   <li><b>Confirmation email</b> — handled by AuthService / EmailChangeService. This is the only
 *       real proof the mailbox exists; levels 1–2 exist purely to avoid wasting sends on addresses
 *       that are provably dead.
 * </ol>
 *
 * <p>Every caller must run its input through {@link #normalize} before persisting or querying, or
 * case variants of the same address become distinct accounts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailValidationService {

  private final EmailDomainService emailDomainService;

  /** Canonical form used for both storage and lookups. Null-safe. */
  public String normalize(String raw) {
    return EmailNormalizer.normalize(raw);
  }

  /**
   * Level 1 only. Use on paths that look up an existing address (login, password reset, resend)
   * where a DNS check would add latency without adding safety.
   *
   * @return the normalized address
   * @throws InvalidEmailException if the address is structurally invalid
   */
  public String normalizeAndValidateFormat(String raw) {
    String normalized = normalize(raw);
    if (!EmailNormalizer.isStructurallyValid(normalized)) {
      throw new InvalidEmailException(
          InvalidEmailException.Reason.EMAIL_INVALID_FORMAT,
          "Email address is not valid",
          normalized == null ? null : EmailNormalizer.suggestCorrection(normalized));
    }
    return normalized;
  }

  /**
   * Levels 1 + 2. Use wherever a NEW address is being attached to an account (profile add/change,
   * email registration) — these are the paths that would otherwise fill the database with typos.
   *
   * <p>An {@code UNVERIFIABLE} DNS result deliberately passes: our resolver being down must not
   * stop a user with a perfectly good address from signing up. The confirmation email remains the
   * source of truth either way.
   *
   * @return the normalized address
   * @throws InvalidEmailException on bad format or a domain that provably accepts no mail
   */
  public String normalizeAndValidateDeliverable(String raw) {
    String normalized = normalizeAndValidateFormat(raw);
    String domain = EmailNormalizer.domainOf(normalized);

    EmailDomainService.DomainStatus status = emailDomainService.resolve(domain);
    if (status == EmailDomainService.DomainStatus.NO_MX) {
      log.info(
          "Rejecting {}: domain '{}' has no MX/A record", EmailNormalizer.mask(normalized), domain);
      throw new InvalidEmailException(
          InvalidEmailException.Reason.EMAIL_DOMAIN_NOT_FOUND,
          "This email domain does not accept mail",
          EmailNormalizer.suggestCorrection(normalized));
    }

    return normalized;
  }

  /**
   * Advisory typo hint, e.g. {@code user@gmial.com} → {@code user@gmail.com}. Never blocks; the UI
   * offers it as a one-click correction.
   */
  public String suggestCorrection(String raw) {
    return EmailNormalizer.suggestCorrection(normalize(raw));
  }
}
