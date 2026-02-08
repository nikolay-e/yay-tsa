package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.ApiTokenEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ApiTokenRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final ApiTokenRepository apiTokenRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom secureRandom = new SecureRandom();

  public AuthService(
      ApiTokenRepository apiTokenRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder) {
    this.apiTokenRepository = apiTokenRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public AuthenticationResult authenticateByName(
      String username,
      String password,
      String deviceId,
      String deviceName,
      String client,
      String version) {
    UserEntity user =
        userRepository
            .findByUsername(username)
            .filter(UserEntity::isActive)
            .orElseThrow(() -> new AuthenticationException("Invalid username or password"));

    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
      throw new AuthenticationException("Invalid username or password");
    }

    userRepository.updateLastLoginAt(user.getId(), OffsetDateTime.now());

    apiTokenRepository.deleteByUserIdAndDeviceId(user.getId(), deviceId);
    apiTokenRepository.flush();

    ApiTokenEntity tokenEntity = new ApiTokenEntity();
    tokenEntity.setUser(user);
    tokenEntity.setToken(generateSecureToken());
    tokenEntity.setDeviceId(deviceId);
    tokenEntity.setDeviceName(deviceName);
    tokenEntity.setLastUsedAt(OffsetDateTime.now());
    apiTokenRepository.save(tokenEntity);

    return new AuthenticationResult(
        user,
        tokenEntity.getToken(),
        tokenEntity.getId().toString(),
        deviceId,
        deviceName,
        client,
        version);
  }

  @Transactional
  public void logout(String token) {
    if (token == null || token.isBlank()) {
      return;
    }

    apiTokenRepository
        .findByTokenAndNotRevoked(token)
        .ifPresent(
            tokenEntity -> {
              tokenEntity.setRevoked(true);
              apiTokenRepository.save(tokenEntity);
            });
  }

  @Transactional(readOnly = true)
  public Optional<UserEntity> validateToken(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }

    return apiTokenRepository
        .findByTokenAndNotRevoked(token)
        .filter(ApiTokenEntity::isValid)
        .map(ApiTokenEntity::getUser);
  }

  @Transactional
  public void updateLastUsedAt(String token) {
    apiTokenRepository
        .findByTokenAndNotRevoked(token)
        .ifPresent(t -> apiTokenRepository.updateLastUsedAt(t.getId(), OffsetDateTime.now()));
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
