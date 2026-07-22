package kz.hrms.splitupauth.util;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;
import kz.hrms.splitupauth.entity.IdentifierType;
import kz.hrms.splitupauth.entity.ServiceAccessType;

/**
 * Pure helpers for the contact a member hands over when joining a room: which identifier types a
 * service accepts, how the raw input is canonicalised, and whether it is well-formed.
 *
 * <p>Static and side-effect free (same contract as {@link EmailNormalizer}) so the join validation,
 * the mapper and the tests all agree on one implementation.
 */
public final class ContactIdentifiers {

  private ContactIdentifiers() {}

  /** Everything that carries a phone number or an operator-side account reference. */
  private static final Set<IdentifierType> PHONE_FAMILY =
      EnumSet.of(IdentifierType.PHONE, IdentifierType.SIM, IdentifierType.ESIM);

  /** KZ numbers, matching the format the auth endpoints already enforce. */
  private static final Pattern PHONE_SHAPE = Pattern.compile("^\\+7\\d{10}$");

  /** Everything a normalize() pass drops from a phone number before matching. */
  private static final Pattern PHONE_NOISE = Pattern.compile("[\\s\\u00A0()\\-.]");

  /**
   * Identifier types a service with the given access type accepts.
   *
   * <p>PHONE keeps the telecom flavours (SIM/eSIM/personal account) alongside a plain number — they
   * are all "the operator finds you by this", and the create-room flow already offers them.
   */
  public static Set<IdentifierType> allowedFor(ServiceAccessType accessType) {
    if (accessType == null) {
      return EnumSet.allOf(IdentifierType.class);
    }
    return switch (accessType) {
      case EMAIL -> EnumSet.of(IdentifierType.EMAIL);
      case PHONE ->
          EnumSet.of(
              IdentifierType.PHONE,
              IdentifierType.SIM,
              IdentifierType.ESIM,
              IdentifierType.ACCOUNT);
      case BOTH -> EnumSet.allOf(IdentifierType.class);
    };
  }

  /** The identifier type to assume when the client didn't send one. */
  public static IdentifierType defaultFor(ServiceAccessType accessType) {
    return accessType == ServiceAccessType.EMAIL ? IdentifierType.EMAIL : IdentifierType.PHONE;
  }

  /**
   * Canonical storage form. Emails go through {@link EmailNormalizer}; phone numbers lose the
   * separators people paste in and a leading {@code 8} becomes {@code +7}. Account ids are only
   * trimmed — their shape is the operator's business, not ours.
   *
   * @return {@code null} for null/blank input, so "not provided" stays distinguishable from "".
   */
  public static String normalize(IdentifierType type, String raw) {
    if (raw == null) {
      return null;
    }
    if (type == IdentifierType.EMAIL) {
      return EmailNormalizer.normalize(raw);
    }
    if (PHONE_FAMILY.contains(type)) {
      String digits = PHONE_NOISE.matcher(raw).replaceAll("");
      if (digits.startsWith("8") && digits.length() == 11) {
        digits = "+7" + digits.substring(1);
      } else if (digits.startsWith("7") && digits.length() == 11) {
        digits = "+" + digits;
      }
      return digits.isEmpty() ? null : digits;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Structural check on an already-normalized value. */
  public static boolean isValidFormat(IdentifierType type, String normalized) {
    if (normalized == null || normalized.isBlank()) {
      return false;
    }
    if (type == IdentifierType.EMAIL) {
      return EmailNormalizer.isStructurallyValid(normalized);
    }
    if (PHONE_FAMILY.contains(type)) {
      return PHONE_SHAPE.matcher(normalized).matches();
    }
    // ACCOUNT: operator-specific, so only reject obviously empty input.
    return normalized.length() >= 4;
  }

  /**
   * Display form kept next to the encrypted value: {@code a*******r@gmail.com} for addresses,
   * {@code +7705*****89} for everything else.
   */
  public static String mask(IdentifierType type, String normalized) {
    if (normalized == null || normalized.isBlank()) {
      return "****";
    }
    if (type == IdentifierType.EMAIL) {
      return EmailNormalizer.mask(normalized);
    }
    if (normalized.length() < 4) {
      return "****";
    }
    if (normalized.length() <= 6) {
      return normalized.charAt(0) + "***" + normalized.charAt(normalized.length() - 1);
    }
    return normalized.substring(0, 4) + "*****" + normalized.substring(normalized.length() - 2);
  }
}
