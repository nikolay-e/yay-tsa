package com.example.mediaserver.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Observability configuration for the media server.
 * Configures metrics collection, distributed tracing, and monitoring.
 */
@Configuration
@EnableAspectJAutoProxy
public class ObservabilityConfig {

    /**
     * Enable @Timed annotation support for method-level metrics
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * Custom metrics can be registered here:
     * - Active stream count
     * - Transcode queue size
     * - Library scan progress
     * - Cache hit rates
     * - Authentication success/failure rates
     */
}