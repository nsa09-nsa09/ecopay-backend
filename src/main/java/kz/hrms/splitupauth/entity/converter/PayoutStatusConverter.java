package kz.hrms.splitupauth.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import kz.hrms.splitupauth.entity.PayoutStatus;

/**
 * Maps {@link PayoutStatus} to/from the existing {@code text} status column, storing the enum
 * constant name verbatim. This keeps the column type and every stored value exactly as before (no
 * migration), while the entity field becomes type-safe. Not auto-applied, so it only affects the
 * explicitly-annotated {@code Payout.status} field and never other String statuses.
 */
@Converter(autoApply = false)
public class PayoutStatusConverter implements AttributeConverter<PayoutStatus, String> {

  @Override
  public String convertToDatabaseColumn(PayoutStatus attribute) {
    return attribute == null ? null : attribute.name();
  }

  @Override
  public PayoutStatus convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return null;
    }
    return PayoutStatus.valueOf(dbData.trim());
  }
}
