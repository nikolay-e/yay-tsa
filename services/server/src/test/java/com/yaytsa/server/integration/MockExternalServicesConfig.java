package com.yaytsa.server.integration;

import static org.mockito.Mockito.*;

import com.yaytsa.server.infrastructure.client.AudioSeparatorClient;
import com.yaytsa.server.infrastructure.client.EmbeddingExtractionClient;
import com.yaytsa.server.infrastructure.client.FeatureExtractionClient;
import com.yaytsa.server.infrastructure.client.RadioSeedClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class MockExternalServicesConfig {

  @Bean
  @Primary
  RadioSeedClient testRadioSeedClient() {
    return new RadioSeedClient(
        org.springframework.web.client.RestClient.builder(), "http://localhost:1");
  }

  @Bean
  @Primary
  FeatureExtractionClient testFeatureExtractionClient() {
    var client = mock(FeatureExtractionClient.class);
    when(client.isAvailable()).thenReturn(false);
    return client;
  }

  @Bean
  @Primary
  EmbeddingExtractionClient testEmbeddingExtractionClient() {
    var client = mock(EmbeddingExtractionClient.class);
    when(client.isAvailable()).thenReturn(false);
    return client;
  }

  @Bean
  @Primary
  AudioSeparatorClient testAudioSeparatorClient() {
    var client = mock(AudioSeparatorClient.class);
    when(client.isHealthy()).thenReturn(false);
    return client;
  }
}
