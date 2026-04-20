package kz.hrms.splitupauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class SplitUpAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(SplitUpAuthApplication.class, args);
    }

}
