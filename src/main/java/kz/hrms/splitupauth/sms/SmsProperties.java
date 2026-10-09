package kz.hrms.splitupauth.sms;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

  private int codeTtlSeconds = 300;
  private int resendCooldownSeconds = 60;
  private int maxAttemptsPerHour = 3;
  private int maxVerifyAttempts = 5;

  /**
   * Per-IP caps on code requests, independent of which number was asked for. The per-phone limits
   * above do nothing against one host walking the number space — every number it tries is "the
   * first request for that number" — and each of those walks costs real money at the SMS provider.
   * Set to 0 to disable.
   */
  private int maxPerIpPerHour = 10;

  private int maxPerIpPerDay = 30;
}
