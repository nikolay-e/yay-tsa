package com.yaytsa.server.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

  private static final int MAX_IP_ATTEMPTS = 20;
  private static final int MAX_USERNAME_ATTEMPTS = 5;

  private final Cache<String, AtomicInteger> ipAttempts;
  private final Cache<String, AtomicInteger> usernameAttempts;

  public LoginRateLimiter() {
    this.ipAttempts =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(10_000).build();
    this.usernameAttempts =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(10_000).build();
  }

  public boolean isBlocked(String ip, String username) {
    AtomicInteger ipCount = ipAttempts.getIfPresent(ip);
    if (ipCount != null && ipCount.get() >= MAX_IP_ATTEMPTS) {
      return true;
    }
    if (username != null) {
      AtomicInteger userCount = usernameAttempts.getIfPresent(username.toLowerCase());
      return userCount != null && userCount.get() >= MAX_USERNAME_ATTEMPTS;
    }
    return false;
  }

  public void recordFailure(String ip, String username) {
    ipAttempts.get(ip, k -> new AtomicInteger()).incrementAndGet();
    if (username != null) {
      usernameAttempts.get(username.toLowerCase(), k -> new AtomicInteger()).incrementAndGet();
    }
  }

  public void recordSuccess(String ip, String username) {
    ipAttempts.invalidate(ip);
    if (username != null) {
      usernameAttempts.invalidate(username.toLowerCase());
    }
  }
}
