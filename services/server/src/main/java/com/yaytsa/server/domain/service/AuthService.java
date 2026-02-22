package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.ApiTokenEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ApiTokenRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final ApiTokenRepository apiTokenRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom secureRandom = new SecureRandom();
  private final String dummyPasswordHash;
  private final int tokenExpiryHours;

  public AuthService(
      ApiTokenRepository apiTokenRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${yaytsa.security.token.expiry-hours:720}") int tokenExpiryHours) {
    this.apiTokenRepository = apiTokenRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.tokenExpiryHours = tokenExpiryHours;
    this.dummyPasswordHash = passwordEncoder.encode("timing-safe-dummy-value");
  }

  @Transactional
  public AuthenticationResult authenticateByName(
      String username,
      String password,
      String deviceId,
      String deviceName,
      String client,
      String version) {
    Optional<UserEntity> userOpt =
        userRepository.findByUsername(username).filter(UserEntity::isActive);

    String passwordHash = userOpt.map(UserEntity::getPasswordHash).orElse(dummyPasswordHash);
    boolean passwordMatches = passwordEncoder.matches(password, passwordHash);

    if (userOpt.isEmpty() || !passwordMatches) {
      throw new AuthenticationException("Invalid username or password");
    }

    UserEntity user = userOpt.get();
    userRepository.updateLastLoginAt(user.getId(), OffsetDateTime.now());

    apiTokenRepository.deleteByUserIdAndDeviceId(user.getId(), deviceId);
    apiTokenRepository.flush();

    String rawToken = generateSecureToken();
    String tokenHash = hashToken(rawToken);

    ApiTokenEntity tokenEntity = new ApiTokenEntity();
    tokenEntity.setUser(user);
    tokenEntity.setToken(tokenHash);
    tokenEntity.setDeviceId(deviceId);
    tokenEntity.setDeviceName(deviceName);
    tokenEntity.setLastUsedAt(OffsetDateTime.now());
    tokenEntity.setExpiresAt(OffsetDateTime.now().plusHours(tokenExpiryHours));
    apiTokenRepository.save(tokenEntity);

    return new AuthenticationResult(
        user, rawToken, tokenEntity.getId().toString(), deviceId, deviceName, client, version);
  }

  @Transactional
  public void logout(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return;
    }

    String tokenHash = hashToken(rawToken);
    apiTokenRepository
        .findByTokenAndNotRevoked(tokenHash)
        .ifPresent(
            tokenEntity -> {
              tokenEntity.setRevoked(true);
              apiTokenRepository.save(tokenEntity);
            });
  }

  @Transactional(readOnly = true)
  public Optional<UserEntity> validateToken(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }

    String tokenHash = hashToken(rawToken);
    return apiTokenRepository
        .findByTokenAndNotRevoked(tokenHash)
        .filter(ApiTokenEntity::isValid)
        .map(ApiTokenEntity::getUser);
  }

  @Transactional
  public void updateLastUsedAt(String rawToken) {
    String tokenHash = hashToken(rawToken);
    apiTokenRepository
        .findByTokenAndNotRevoked(tokenHash)
        .ifPresent(t -> apiTokenRepository.updateLastUsedAt(t.getId(), OffsetDateTime.now()));
  }

  @Transactional
  public int cleanupExpiredTokens() {
    return apiTokenRepository.deleteExpiredTokens(OffsetDateTime.now());
  }

  static String hashToken(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private String generateSecureToken() {
    byte[] tokenBytes = new byte[32];
    secureRandom.nextBytes(tokenBytes);
    return HexFormat.of().formatHex(tokenBytes);
  }

  public static class AuthenticationException extends RuntimeException {
    public AuthenticationException(String message) {
      super(message);
    }
  }

  public record AuthenticationResult(
      UserEntity user,
      String accessToken,
      String sessionId,
      String deviceId,
      String deviceName,
      String client,
      String version) {}
}
