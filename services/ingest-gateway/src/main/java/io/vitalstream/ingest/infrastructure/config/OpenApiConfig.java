package io.vitalstream.ingest.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata for the generated OpenAPI document served at {@code /v3/api-docs}. */
@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("VitalStream ingest-gateway")
                .version("v1")
                .description(
                    "Accepts vital signs as device batches, FHIR R4 Observations or HL7 v2 ORU^R01 "
                        + "and publishes them to Kafka topic vitals.raw."));
  }
}
