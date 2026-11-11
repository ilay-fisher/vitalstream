package io.vitalstream.clinical.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the dashboard (nginx build on :3000, Vite dev server on :5173). */
@Configuration
@EnableConfigurationProperties(ClinicalProperties.class)
public class WebConfig implements WebMvcConfigurer {

  private final ClinicalProperties properties;

  public WebConfig(ClinicalProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(properties.cors().allowedOrigins())
        .allowedMethods("GET", "POST", "OPTIONS")
        .allowedHeaders("*")
        .exposedHeaders("X-Correlation-Id");
  }
}
