package kz.hrms.splitupauth.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "ecopay.sms",
        name = "provider",
        havingValue = "logging",
        matchIfMissing = true
)
public class LoggingSmsProvider implements SmsService {

    @Override
    public void sendVerificationCode(String phone, String code) {
        log.warn("[SMS:LOGGING] Verification code for {} is {}", phone, code);
    }
}
