package io.vitalstream.clinical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the VitalStream clinical API. */
@SpringBootApplication
public class ClinicalApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ClinicalApiApplication.class, args);
  }
}
