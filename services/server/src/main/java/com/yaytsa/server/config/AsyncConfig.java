package com.yaytsa.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
public class AsyncConfig {

  @Bean("applicationTaskExecutor")
  public AsyncTaskExecutor applicationTaskExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("app-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(50);
    return executor;
  }

  @Bean("signalAsyncExecutor")
  public AsyncTaskExecutor signalAsyncExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("signal-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(10);
    return executor;
  }

  @Bean("recommendationExecutor")
  public AsyncTaskExecutor recommendationExecutor() {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("recommend-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(5);
    return executor;
  }
}
