package com.yaytsa.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

  @Bean
  public CacheManager cacheManager() {
    SimpleCacheManager cacheManager = new SimpleCacheManager();
    cacheManager.setCaches(
        List.of(
            buildCache("metadata-genius", 5000, 7, TimeUnit.DAYS),
            buildCache("metadata-musicbrainz", 5000, 7, TimeUnit.DAYS),
            buildCache("metadata-lastfm", 5000, 7, TimeUnit.DAYS),
            buildCache("metadata-spotify", 5000, 7, TimeUnit.DAYS),
            buildCache("metadata-itunes", 5000, 7, TimeUnit.DAYS),
            buildCache("api-tokens", 1000, 5, TimeUnit.MINUTES),
            buildCache("items", 10000, 30, TimeUnit.MINUTES),
            buildCache("images", 500, 1, TimeUnit.HOURS),
            buildCache("audio-separator-url", 100, 5, TimeUnit.MINUTES)));
    return cacheManager;
  }

  private CaffeineCache buildCache(String name, long maxSize, long duration, TimeUnit unit) {
    return new CaffeineCache(
        name,
        Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(duration, unit)
            .recordStats()
            .build());
  }
}
