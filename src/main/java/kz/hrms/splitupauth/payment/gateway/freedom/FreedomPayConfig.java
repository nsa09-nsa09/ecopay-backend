package kz.hrms.splitupauth.payment.gateway.freedom;

import kz.hrms.splitupauth.payment.fiscal.FiscalProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({FreedomPayProperties.class, FiscalProperties.class})
public class FreedomPayConfig {}
