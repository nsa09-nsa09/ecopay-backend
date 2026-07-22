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
    when(emailDomainService.resolve("example.com"))
        .thenReturn(EmailDomainService.DomainStatus.HAS_MX);

    assertEquals("user@example.com", service.normalizeAndValidateDeliverable("User@Example.com"));
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
    when(emailDomainService.resolve("example.com"))
        .thenReturn(EmailDomainService.DomainStatus.UNVERIFIABLE);

    assertEquals("user@example.com", service.normalizeAndValidateDeliverable("user@example.com"));
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
