package kz.hrms.splitupauth.payment.gateway.freedom;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ecopay.payments.freedompay")
public class FreedomPayProperties {

    private String baseUrl = "https://test-api.freedompay.kz";
    private String merchantId = "";
    private String secretKey = "";
    private String payoutSecretKey = "";
    private String resultUrl = "";
    private String payoutResultUrl = "";
    private String successUrl = "";
    private String failureUrl = "";
    /** "1" = sandbox/test mode, "0" = real charges. */
    private String testMode = "1";
    /**
     * Lifetime of a saved-card recurring profile. Freedom Pay REQUIRES
     * pg_recurring_lifetime whenever pg_recurring_start=1, and caps it at 156, so this is
     * sent on every card-save init_payment and must stay &lt;= 156.
     */
    private int recurringLifetimeDays = 156;
}
