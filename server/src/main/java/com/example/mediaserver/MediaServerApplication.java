package com.example.mediaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Yaytsa Media Server Application
 *
 * A Jellyfin-compatible media server built with Spring Boot 3.3 and Java 21 virtual threads.
 * This server provides streaming capabilities, library management, and playback tracking
 * for music collections.
 */
@SpringBootApplication
@EnableAsync
@EnableCaching
@ConfigurationPropertiesScan
public class MediaServerApplication {

    public static void main(String[] args) {
        // Enable virtual threads for the application
        System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory",
                          "java.util.concurrent.Executors$VirtualThreadFactory");

        SpringApplication.run(MediaServerApplication.class, args);
    }
}
