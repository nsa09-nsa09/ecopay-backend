package kz.hrms.splitupauth.payment.gateway.freedom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FreedomPayProperties.class)
public class FreedomPayConfig {
}
