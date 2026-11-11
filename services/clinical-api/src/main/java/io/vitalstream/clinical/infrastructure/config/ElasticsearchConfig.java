package io.vitalstream.clinical.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP client for the Elasticsearch REST API with bounded timeouts. */
@Configuration
public class ElasticsearchConfig {

  @Bean
  RestClient elasticsearchClient(ClinicalProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3_000);
    factory.setReadTimeout(10_000);
    return RestClient.builder()
        .baseUrl(properties.elasticsearch().url())
        .requestFactory(factory)
        .build();
  }
}
