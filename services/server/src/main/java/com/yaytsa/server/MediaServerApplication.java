package com.yaytsa.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Yaytsa Media Server Application
 *
 * <p>A Jellyfin-compatible media server built with Spring Boot 3.3 and Java 21 virtual threads.
 * This server provides streaming capabilities, library management, and playback tracking for music
 * collections.
 */
@SpringBootApplication
@EnableAsync
@EnableCaching
@ConfigurationPropertiesScan
public class MediaServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(MediaServerApplication.class, args);
  }
}
