package kz.hrms.splitupauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@EnableScheduling
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class SplitUpAuthApplication {
    public static void main(String[] args) {
        // Pin the JVM clock to Asia/Almaty (+05:00) before Spring starts so every
        // entity using LocalDateTime.now() and every JDBC timestamp conversion
        // sees the same wall clock as the product is operated in.
        System.setProperty("user.timezone", "Asia/Almaty");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Almaty"));
        SpringApplication.run(SplitUpAuthApplication.class, args);
    }

}
