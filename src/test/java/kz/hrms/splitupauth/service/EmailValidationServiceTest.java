package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import kz.hrms.splitupauth.exception.InvalidEmailException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Levels 1 and 2 of the pipeline, and the boundary between them. */
@ExtendWith(MockitoExtension.class)
class EmailValidationServiceTest {

  @Mock EmailDomainService emailDomainService;

  @InjectMocks EmailValidationService service;

  // ===================== level 1: format =====================

  @Test
  void formatCheck_normalizesAndAccepts() {
    assertEquals("user@gmail.com", service.normalizeAndValidateFormat("  User@GMAIL.com "));
  }

  @Test
  void formatCheck_rejectsMalformed_withReasonCode() {
    InvalidEmailException ex =
        assertThrows(
            InvalidEmailException.class, () -> service.normalizeAndValidateFormat("not-an-email"));
    assertEquals(InvalidEmailException.Reason.EMAIL_INVALID_FORMAT, ex.getReason());
  }

  @Test
  void formatCheck_neverTouchesDns() {
    // The whole point of the level split: login and password-reset must not
    // pay for (or be blocked by) a DNS lookup.
    service.normalizeAndValidateFormat("user@example.com");
    verifyNoInteractions(emailDomainService);
  }

  // ===================== level 2: domain =====================

  @Test
  void deliverableCheck_acceptsDomainWithMx() {
    // B18: can't use example.com here — it is now rejected as an RFC 2606 placeholder before the
    // DNS stub even runs. Pick a plausible-looking real-world domain instead.
    when(emailDomainService.resolve("realsite.io"))
        .thenReturn(EmailDomainService.DomainStatus.HAS_MX);

    assertEquals("user@realsite.io", service.normalizeAndValidateDeliverable("User@Realsite.io"));
  }

  @Test
  void deliverableCheck_rejectsDomainWithoutMx_andSuggestsTheLikelyTypo() {
    when(emailDomainService.resolve("gmial.com")).thenReturn(EmailDomainService.DomainStatus.NO_MX);

    InvalidEmailException ex =
        assertThrows(
            InvalidEmailException.class,
            () -> service.normalizeAndValidateDeliverable("user@gmial.com"));

    assertEquals(InvalidEmailException.Reason.EMAIL_DOMAIN_NOT_FOUND, ex.getReason());
    assertEquals("user@gmail.com", ex.getSuggestion());
  }

  @Test
  void deliverableCheck_passesWhenDnsIsUnreachable() {
    // A resolver outage on our side must not stop a user with a perfectly good
    // address from signing up — the confirmation email is still the real test.
    // B18: using realsite.io because example.com is now rejected as a reserved placeholder.
    when(emailDomainService.resolve("realsite.io"))
        .thenReturn(EmailDomainService.DomainStatus.UNVERIFIABLE);

    assertEquals("user@realsite.io", service.normalizeAndValidateDeliverable("user@realsite.io"));
  }

  @Test
  void deliverableCheck_rejectsReservedPlaceholderDomains() {
    // B18 regression: RFC 2606 reserved domains and example.{com,org,net} must be rejected
    // before any DNS lookup, so dev/QA accounts can't be created on them.
    for (String addr :
        new String[] {
          "qa@example.com", "foo@example.org", "bar@example.net",
          "baz@test.invalid", "qux@service.test", "x@dev.localhost"
        }) {
      InvalidEmailException ex =
          assertThrows(
              InvalidEmailException.class,
              () -> service.normalizeAndValidateDeliverable(addr),
              "Expected reserved-domain rejection for " + addr);
      assertEquals(InvalidEmailException.Reason.EMAIL_DOMAIN_NOT_FOUND, ex.getReason());
    }
    verifyNoInteractions(emailDomainService);
  }

  @Test
  void deliverableCheck_failsFormatBeforeSpendingADnsLookup() {
    assertThrows(
        InvalidEmailException.class, () -> service.normalizeAndValidateDeliverable("user@nodot"));
    verifyNoInteractions(emailDomainService);
  }

  // ===================== suggestions =====================

  @Test
  void suggestion_isAdvisoryAndNullForGoodDomains() {
    assertEquals("user@gmail.com", service.suggestCorrection("USER@GMIAL.COM"));
    assertNull(service.suggestCorrection("user@gmail.com"));
  }
}
