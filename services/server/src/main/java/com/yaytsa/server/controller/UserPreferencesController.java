package com.yaytsa.server.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yaytsa.server.domain.service.TasteProfileService;
import com.yaytsa.server.domain.service.UserPreferencesService;
import com.yaytsa.server.dto.request.UpdateUserPreferencesRequest;
import com.yaytsa.server.dto.response.TasteProfileResponse;
import com.yaytsa.server.dto.response.UserPreferencesResponse;
import com.yaytsa.server.error.ResourceNotFoundException;
import com.yaytsa.server.error.ResourceType;
import com.yaytsa.server.infrastructure.persistence.entity.TasteProfileEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserPreferenceContractEntity;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/users/{userId}")
public class UserPreferencesController {

  private final UserPreferencesService preferencesService;
  private final TasteProfileService tasteProfileService;
  private final ObjectMapper objectMapper;

  public UserPreferencesController(
      UserPreferencesService preferencesService,
      TasteProfileService tasteProfileService,
      ObjectMapper objectMapper) {
    this.preferencesService = preferencesService;
    this.tasteProfileService = tasteProfileService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/preferences")
  public ResponseEntity<UserPreferencesResponse> getPreferences(
      @PathVariable UUID userId, @AuthenticationPrincipal AuthenticatedUser user) {
    verifyAccess(userId, user);
    return ResponseEntity.ok(toPreferencesResponse(preferencesService.getPreferences(userId)));
  }

  @PutMapping("/preferences")
  public ResponseEntity<UserPreferencesResponse> updatePreferences(
      @PathVariable UUID userId,
      @RequestBody UpdateUserPreferencesRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    verifyAccess(userId, user);
    var entity =
        preferencesService.updatePreferences(
            userId,
            request.hardRules(),
            request.softPrefs(),
            request.djStyle(),
            request.redLines());
    return ResponseEntity.ok(toPreferencesResponse(entity));
  }

  @GetMapping("/taste-profile")
  public ResponseEntity<TasteProfileResponse> getTasteProfile(
      @PathVariable UUID userId, @AuthenticationPrincipal AuthenticatedUser user) {
    verifyAccess(userId, user);
    TasteProfileEntity entity = tasteProfileService.getProfile(userId);
    if (entity == null) throw new ResourceNotFoundException(ResourceType.TasteProfile, userId);
    return ResponseEntity.ok(
        new TasteProfileResponse(
            entity.getUserId().toString(), parseJson(entity.getProfile()),
            entity.getSummaryText(), entity.getRebuiltAt()));
  }

  private void verifyAccess(UUID userId, AuthenticatedUser user) {
    if (!user.getUserEntity().isAdmin() && !userId.equals(user.getUserEntity().getId()))
      throw new org.springframework.security.access.AccessDeniedException("Access denied");
  }

  private UserPreferencesResponse toPreferencesResponse(UserPreferenceContractEntity entity) {
    return new UserPreferencesResponse(
        entity.getUserId().toString(), parseJson(entity.getHardRules()),
        parseJson(entity.getSoftPrefs()), parseJson(entity.getDjStyle()),
        parseJson(entity.getRedLines()), entity.getUpdatedAt());
  }

  private Object parseJson(String json) {
    if (json == null) return null;
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (JsonProcessingException e) {
      return json;
    }
  }
}
