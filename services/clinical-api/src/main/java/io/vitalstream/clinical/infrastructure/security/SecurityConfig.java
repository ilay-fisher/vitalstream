package io.vitalstream.clinical.infrastructure.security;

import io.vitalstream.clinical.application.TokenIssuer;
import io.vitalstream.clinical.domain.Role;
import io.vitalstream.clinical.infrastructure.config.ClinicalProperties;
import io.vitalstream.clinical.infrastructure.config.ConditionalOnDevTokens;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * clinical-api as an OAuth2 resource server (CONTRACTS 4.2). The chain is stateless and bearer
 * only, so CSRF protection (which guards cookie sessions) is off. The servlet filters for the host
 * allowlist and the correlation id run before Spring Security's chain.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ClinicalProperties.class)
public class SecurityConfig {

  private static final String[] ANY_ROLE = {
    Role.CLINICIAN.name(), Role.VIEWER.name(), Role.SERVICE.name()
  };

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems)
      throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RoleAuthoritiesConverter());
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth ->
            auth.requestMatchers(HttpMethod.POST, "/api/v1/dev/token")
                .permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**")
                .permitAll()
                .requestMatchers("/actuator/prometheus")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/**")
                .hasAnyRole(ANY_ROLE)
                .requestMatchers(HttpMethod.POST, "/api/v1/alerts/*/ack")
                .hasRole(Role.CLINICIAN.name())
                .requestMatchers("/api/**")
                .denyAll()
                .anyRequest()
                .hasAnyRole(ANY_ROLE))
        .oauth2ResourceServer(oauth ->
            oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(problems)
                .accessDeniedHandler(problems))
        .exceptionHandling(
            errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems));
    return http.build();
  }

  @Bean
  ProblemSecurityHandlers problemSecurityHandlers() {
    return new ProblemSecurityHandlers();
  }

  @Bean
  JwtDecoder jwtDecoder(ClinicalProperties properties) {
    return ClinicalJwtDecoders.create(properties.jwt());
  }

  @Bean
  @ConditionalOnDevTokens
  TokenIssuer tokenIssuer(ClinicalProperties properties, Clock clock) {
    return new NimbusTokenIssuer(properties.jwt(), clock);
  }
}
