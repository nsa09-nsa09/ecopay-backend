package kz.hrms.splitupauth.security;

import kz.hrms.splitupauth.config.AvatarUploadProperties;
import kz.hrms.splitupauth.config.CorsProperties;
import kz.hrms.splitupauth.sms.SmsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({CorsProperties.class, SmsProperties.class, AvatarUploadProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .headers(headers -> headers
                        // Browser security headers (DoD). nosniff + frameOptions DENY are also
                        // Spring defaults; CSP/HSTS/Referrer-Policy are added explicitly.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/login/2fa/verify",
                                "/api/v1/auth/login/2fa/resend",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/reset-password/confirm",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/webhooks/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalog/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/reputation/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/service-reviews/featured").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/site/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/avatars/**").permitAll()
                        // Public room browsing only: the catalog list and a single room by id.
                        // Everything deeper under a room (members, membership) requires auth,
                        // and falls through to anyRequest().authenticated() below.
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/joined").authenticated()
                        // Single room is public (catalog detail); deeper paths (e.g. /{id}/members)
                        // fall through to authenticated() to avoid leaking member PII.
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/*").permitAll()
                        .requestMatchers("/api/v1/staff/**").hasAnyAuthority("ADMIN", "SUPPORT")
                        .requestMatchers("/api/v1/admin/**").hasAuthority("ADMIN")
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
