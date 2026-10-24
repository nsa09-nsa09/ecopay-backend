package kz.hrms.splitupauth.payment.fiscal;

import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ecopay.payments.fiscal")
public class FiscalProperties {

  private static final Set<Integer> ALLOWED_TAX_TYPES = Set.of(0, 3, 7, 11);

  private boolean enabled = false;

  /** FreedomPay tax_type for the member's tariff share position. */
  private int shareTaxType = 0;

  /** FreedomPay tax_type for the EcoPay commission position. */
  private int commissionTaxType = 0;

  /**
   * Template for the share receipt line name. Placeholders: {service}, {tariff}, {period}. Defaults
   * to the Russian string required by the task spec.
   */
  private String shareNameTemplate = "Доля подписки {service} ({tariff}), период {period}";

  /** Name for the EcoPay commission receipt line. */
  private String commissionName = "Сервисный сбор EcoPay";

  public boolean isValidTaxType(int type) {
    return ALLOWED_TAX_TYPES.contains(type);
  }

  public void validate() {
    if (!isValidTaxType(shareTaxType)) {
      throw new IllegalStateException(
          "ecopay.payments.fiscal.share-tax-type must be one of "
              + ALLOWED_TAX_TYPES
              + ", got "
              + shareTaxType);
    }
    if (!isValidTaxType(commissionTaxType)) {
      throw new IllegalStateException(
          "ecopay.payments.fiscal.commission-tax-type must be one of "
              + ALLOWED_TAX_TYPES
              + ", got "
              + commissionTaxType);
    }
  }
}
