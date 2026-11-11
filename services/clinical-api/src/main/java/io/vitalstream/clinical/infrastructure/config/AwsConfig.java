package io.vitalstream.clinical.infrastructure.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** SQS client; an endpoint override (LocalStack) also switches to dummy static credentials. */
@Configuration
public class AwsConfig {

  @Bean(destroyMethod = "close")
  SqsClient sqsClient(ClinicalProperties properties) {
    ClinicalProperties.Sqs sqs = properties.sqs();
    SqsClientBuilder builder =
        SqsClient.builder()
            .region(Region.of(sqs.region()))
            .httpClientBuilder(UrlConnectionHttpClient.builder());
    if (!sqs.endpoint().isBlank()) {
      builder
          .endpointOverride(URI.create(sqs.endpoint()))
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
    }
    return builder.build();
  }
}
