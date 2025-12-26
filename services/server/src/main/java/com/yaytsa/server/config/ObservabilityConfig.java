package com.yaytsa.server.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableAspectJAutoProxy
public class ObservabilityConfig {

    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private final AtomicInteger karaokeProcessingJobs = new AtomicInteger(0);

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public MediaServerMetrics mediaServerMetrics(MeterRegistry registry) {
        Gauge.builder("yaytsa.streams.active", activeStreams, AtomicInteger::get)
            .description("Number of active audio streams")
            .register(registry);

        Gauge.builder("yaytsa.karaoke.processing", karaokeProcessingJobs, AtomicInteger::get)
            .description("Number of karaoke jobs currently processing")
            .register(registry);

        Counter authSuccessCounter = Counter.builder("yaytsa.auth.success")
            .description("Number of successful authentications")
            .register(registry);

        Counter authFailureCounter = Counter.builder("yaytsa.auth.failure")
            .description("Number of failed authentications")
            .register(registry);

        Counter streamStartCounter = Counter.builder("yaytsa.streams.started")
            .description("Total number of streams started")
            .register(registry);

        Counter karaokeRequestCounter = Counter.builder("yaytsa.karaoke.requests")
            .description("Total number of karaoke processing requests")
            .register(registry);

        return new MediaServerMetrics(
            activeStreams,
            karaokeProcessingJobs,
            authSuccessCounter,
            authFailureCounter,
            streamStartCounter,
            karaokeRequestCounter
        );
    }

    public record MediaServerMetrics(
        AtomicInteger activeStreams,
        AtomicInteger karaokeProcessingJobs,
        Counter authSuccessCounter,
        Counter authFailureCounter,
        Counter streamStartCounter,
        Counter karaokeRequestCounter
    ) {
        public void streamStarted() {
            activeStreams.incrementAndGet();
            streamStartCounter.increment();
        }

        public void streamEnded() {
            activeStreams.decrementAndGet();
        }

        public void karaokeJobStarted() {
            karaokeProcessingJobs.incrementAndGet();
            karaokeRequestCounter.increment();
        }

        public void karaokeJobEnded() {
            karaokeProcessingJobs.decrementAndGet();
        }

        public void authSuccess() {
            authSuccessCounter.increment();
        }

        public void authFailure() {
            authFailureCounter.increment();
        }
    }
}
