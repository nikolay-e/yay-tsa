package com.yaytsa.server.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

  private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

  @Bean("signalAsyncExecutor")
  public ExecutorService signalAsyncExecutor() {
    return new ThreadPoolExecutor(
        0,
        10,
        60L,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(50),
        Thread.ofVirtual().factory(),
        (r, executor) -> log.warn("Signal async executor queue full, dropping task"));
  }
}
