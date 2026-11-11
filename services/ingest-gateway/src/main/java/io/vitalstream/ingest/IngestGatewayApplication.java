package io.vitalstream.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point of the VitalStream ingest gateway. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(IngestGatewayApplication.class, args);
  }
}
