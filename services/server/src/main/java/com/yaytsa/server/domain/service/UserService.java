package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public Optional<UserEntity> findById(UUID userId) {
    return userRepository.findById(userId);
  }

  public Optional<UserEntity> findByUsername(String username) {
    return userRepository.findByUsername(username);
  }

  public UserEntity getCurrentUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new UnauthorizedException("User not authenticated");
    }

    String username = authentication.getName();
    return userRepository
        .findByUsername(username)
        .orElseThrow(() -> new UserNotFoundException("User not found: " + username));
  }

  public static class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
      super(message);
    }
  }

  public static class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) {
      super(message);
    }
  }
}
