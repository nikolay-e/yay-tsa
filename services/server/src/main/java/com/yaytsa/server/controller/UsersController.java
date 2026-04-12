package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.UserService;
import com.yaytsa.server.dto.response.UserResponse;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.mapper.UserMapper;
import com.yaytsa.server.util.UuidUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/Users")
@Tag(name = "Users", description = "User management")
@Transactional(readOnly = true)
public class UsersController {

  private final UserService userService;
  private final UserMapper userMapper;

  public UsersController(UserService userService, UserMapper userMapper) {
    this.userService = userService;
    this.userMapper = userMapper;
  }

  @Operation(summary = "Get user by ID", description = "Retrieve user information by user ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved user"),
        @ApiResponse(responseCode = "400", description = "Invalid user ID format"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PreAuthorize("@userAccess.isOwnerOrAdmin(#userId, authentication)")
  @GetMapping("/{userId}")
  public ResponseEntity<UserResponse> getUserById(
      @Parameter(description = "User ID") @PathVariable String userId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = UuidUtils.parseUuid(userId);
    if (userUuid == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid user ID");
    }

    UserEntity user =
        userService
            .findById(userUuid)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.User, userUuid));

    UserResponse userDto = userMapper.toDto(user);
    return ResponseEntity.ok(userDto);
  }
}
