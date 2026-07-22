package kz.hrms.splitupauth.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kz.hrms.splitupauth.entity.IdentifierType;
import kz.hrms.splitupauth.entity.ServiceAccessType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which contact a service accepts, and how the raw input is canonicalised before storage. */
class ContactIdentifiersTest {

  // ===================== allowedFor =====================

  @Test
  void allowedFor_emailServicesTakeOnlyAddresses() {
    assertEquals(
        java.util.Set.of(IdentifierType.EMAIL),
        ContactIdentifiers.allowedFor(ServiceAccessType.EMAIL));
  }

  @Test
  void allowedFor_phoneServicesKeepTheTelecomFlavours() {
    // A SIM/eSIM/personal-account reference is still "the operator finds you by
    // this", so the create-room options stay valid for phone-keyed services.
    var allowed = ContactIdentifiers.allowedFor(ServiceAccessType.PHONE);
    assertTrue(allowed.contains(IdentifierType.PHONE));
    assertTrue(allowed.contains(IdentifierType.SIM));
    assertTrue(allowed.contains(IdentifierType.ESIM));
    assertTrue(allowed.contains(IdentifierType.ACCOUNT));
    assertFalse(allowed.contains(IdentifierType.EMAIL));
  }

  @Test
  void allowedFor_bothTakesAnything() {
    assertTrue(
        ContactIdentifiers.allowedFor(ServiceAccessType.BOTH).contains(IdentifierType.EMAIL));
    assertTrue(
        ContactIdentifiers.allowedFor(ServiceAccessType.BOTH).contains(IdentifierType.PHONE));
  }

  @Test
  void defaultFor_picksTheServicesNaturalIdentifier() {
    assertEquals(IdentifierType.EMAIL, ContactIdentifiers.defaultFor(ServiceAccessType.EMAIL));
    assertEquals(IdentifierType.PHONE, ContactIdentifiers.defaultFor(ServiceAccessType.PHONE));
    assertEquals(IdentifierType.PHONE, ContactIdentifiers.defaultFor(ServiceAccessType.BOTH));
  }

  // ===================== normalize =====================

  @Test
  void normalize_emailGoesThroughTheEmailRules() {
    assertEquals(
        "user@gmail.com", ContactIdentifiers.normalize(IdentifierType.EMAIL, " User@Gmail.COM "));
  }

  @ParameterizedTest
  @ValueSource(strings = {"+7 705 123 45 67", "+7 (705) 123-45-67", "87051234567", "77051234567"})
  void normalize_phoneAcceptsWhatPeopleActuallyType(String raw) {
    assertEquals("+77051234567", ContactIdentifiers.normalize(IdentifierType.PHONE, raw));
  }

  @Test
  void normalize_accountIsOnlyTrimmed() {
    // Operator contract ids have no shape we can assume — leave them alone.
    assertEquals("KZ-4471-B", ContactIdentifiers.normalize(IdentifierType.ACCOUNT, "  KZ-4471-B "));
  }

  @Test
  void normalize_blankStaysDistinguishableFromMissing() {
    assertNull(ContactIdentifiers.normalize(IdentifierType.PHONE, "   "));
    assertNull(ContactIdentifiers.normalize(IdentifierType.EMAIL, ""));
    assertNull(ContactIdentifiers.normalize(IdentifierType.EMAIL, null));
  }

  // ===================== isValidFormat =====================

  @Test
  void isValidFormat_emailRejectsWhatTheEmailRulesReject() {
    assertTrue(ContactIdentifiers.isValidFormat(IdentifierType.EMAIL, "user@gmail.com"));
    assertFalse(ContactIdentifiers.isValidFormat(IdentifierType.EMAIL, "user@gmail"));
    assertFalse(ContactIdentifiers.isValidFormat(IdentifierType.EMAIL, "+77051234567"));
  }

  @Test
  void isValidFormat_phoneMatchesTheAuthEndpointsFormat() {
    assertTrue(ContactIdentifiers.isValidFormat(IdentifierType.PHONE, "+77051234567"));
    assertFalse(ContactIdentifiers.isValidFormat(IdentifierType.PHONE, "+7705123456"));
    assertFalse(ContactIdentifiers.isValidFormat(IdentifierType.PHONE, "user@gmail.com"));
  }

  @Test
  void isValidFormat_accountOnlyRejectsObviousJunk() {
    assertTrue(ContactIdentifiers.isValidFormat(IdentifierType.ACCOUNT, "KZ-4471-B"));
    assertFalse(ContactIdentifiers.isValidFormat(IdentifierType.ACCOUNT, "12"));
  }

  // ===================== mask =====================

  @Test
  void mask_keepsTheDomainVisibleForAddresses() {
    assertEquals(
        "a*******r@gmail.com",
        ContactIdentifiers.mask(IdentifierType.EMAIL, "alexander@gmail.com"));
  }

  @Test
  void mask_keepsTheTailVisibleForNumbers() {
    assertEquals("+770*****67", ContactIdentifiers.mask(IdentifierType.PHONE, "+77051234567"));
  }
}
