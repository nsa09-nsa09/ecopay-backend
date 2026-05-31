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
}
