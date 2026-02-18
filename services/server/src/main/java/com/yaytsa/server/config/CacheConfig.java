package com.yaytsa.server.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration using Caffeine.
 *
 * <p>Configures multiple caches with different TTL and size limits for various use cases.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  @Bean
  public CacheManager cacheManager() {
    CaffeineCacheManager cacheManager = new CaffeineCacheManager();

    // Default cache configuration
    cacheManager.setCaffeine(
        Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats());

    // Register all cache names
    cacheManager.setCacheNames(
        List.of(
            "metadata-genius", // Genius API results
            "metadata-musicbrainz", // MusicBrainz API results
            "metadata-lastfm", // Last.fm API results
            "metadata-spotify", // Spotify API results
            "metadata-itunes", // iTunes Search API results
            "api-tokens", // Token validation
            "items", // Item lookups
            "images" // Image responses
            ));

    return cacheManager;
  }

  @Bean
  public Caffeine<Object, Object> metadataGeniusCacheConfig() {
    // Genius results cached for 7 days
    return Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .recordStats();
  }

  @Bean
  public Caffeine<Object, Object> metadataMusicbrainzCacheConfig() {
    // MusicBrainz results cached for 7 days (very stable data)
    return Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .recordStats();
  }

  @Bean
  public Caffeine<Object, Object> metadataLastfmCacheConfig() {
    // Last.fm results cached for 7 days
    return Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .recordStats();
  }

  @Bean
  public Caffeine<Object, Object> metadataSpotifyCacheConfig() {
    // Spotify results cached for 7 days
    return Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .recordStats();
  }

  @Bean
  public Caffeine<Object, Object> metadataItunesCacheConfig() {
    // iTunes results cached for 7 days
    return Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(7, TimeUnit.DAYS)
        .recordStats();
  }

  @Bean
  public Caffeine<Object, Object> apiTokensCacheConfig() {
    // Short TTL for security - force DB check periodically
    return Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(5, TimeUnit.MINUTES);
  }

  @Bean
  public Caffeine<Object, Object> itemsCacheConfig() {
    // Medium TTL for item lookups
    return Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(30, TimeUnit.MINUTES);
  }

  @Bean
  public Caffeine<Object, Object> imagesCacheConfig() {
    // Longer TTL for images (rarely change)
    return Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .recordStats();
  }
}
