package com.yaytsa.server.domain.service;

import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class UserManagementService {

  private static final String PASSWORD_CHARS =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#%";
  private static final int GENERATED_PASSWORD_LENGTH = 16;

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecureRandom secureRandom = new SecureRandom();

  public UserManagementService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional(readOnly = true)
  public List<UserEntity> listAll() {
    return userRepository.findAll();
  }

  public record CreatedUser(UserEntity user, String plainPassword) {}

  public CreatedUser createUser(String username, String displayName, boolean isAdmin) {
    if (username == null || username.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username is required");
    }
    String trimmed = username.trim();
    if (trimmed.length() < 3 || trimmed.length() > 50) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 3–50 characters");
    }
    if (userRepository.existsByUsername(trimmed)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
    }

    String plainPassword = generatePassword();

    UserEntity user = new UserEntity();
    user.setUsername(trimmed);
    user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName.trim() : null);
    user.setPasswordHash(passwordEncoder.encode(plainPassword));
    user.setAdmin(isAdmin);
    user.setActive(true);

    return new CreatedUser(userRepository.save(user), plainPassword);
  }

  public void deleteUser(UUID userId, UUID currentUserId) {
    if (userId.equals(currentUserId)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete yourself");
    }
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userId.toString()));
    userRepository.delete(user);
  }

  public String resetPassword(UUID userId, UUID currentUserId) {
    if (userId.equals(currentUserId)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Use the change password form to update your own password");
    }
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userId.toString()));
    String plainPassword = generatePassword();
    user.setPasswordHash(passwordEncoder.encode(plainPassword));
    return plainPassword;
  }

  private String generatePassword() {
    StringBuilder sb = new StringBuilder(GENERATED_PASSWORD_LENGTH);
    for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
      sb.append(PASSWORD_CHARS.charAt(secureRandom.nextInt(PASSWORD_CHARS.length())));
    }
    return sb.toString();
  }
}
