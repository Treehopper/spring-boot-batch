package com.example.poc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The external-scheduler API ({@code /api/**}) is protected with a technical user (HTTP basic).
 * <p>
 * Everything else (batch console, the scheduler's own UI and API, H2 console, Swagger) is left open for local
 * testing. For anything beyond a PoC, protect them with the host application's security (e.g. the company SSO).
 * The Quartz Manager UI treats a 404 on {@code /quartz-manager/auth/whoami} as "security disabled".
 */
@Configuration
public class SecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    return http
      .securityMatcher("/api/**")
      .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("BATCH_API"))
      .httpBasic(Customizer.withDefaults())
      .csrf(csrf -> csrf.disable())
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain openForLocalTesting(HttpSecurity http) throws Exception {
    return http
      .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
      .csrf(csrf -> csrf.disable())
      .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())) // H2 console uses frames
      .build();
  }

  @Bean
  UserDetailsService users(PocProperties properties) {
    return new InMemoryUserDetailsManager(User.withUsername(properties.api().user())
      .password("{noop}" + properties.api().password())
      .roles("BATCH_API")
      .build());
  }
}
