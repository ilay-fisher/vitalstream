package io.vitalstream.clinical.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata served at {@code /v3/api-docs} and Swagger UI at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI clinicalApiDocs() {
    return new OpenAPI()
        .info(
            new Info()
                .title("VitalStream clinical-api")
                .version("v1")
                .description("Patients, vitals history, alerts, acknowledgement and live feed"));
  }
}
