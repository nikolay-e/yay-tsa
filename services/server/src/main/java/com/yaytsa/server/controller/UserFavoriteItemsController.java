package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ItemService;
import com.yaytsa.server.domain.service.PlayStateService;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import com.yaytsa.server.util.UuidUtils;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/UserFavoriteItems")
public class UserFavoriteItemsController {

  private final ItemService itemService;
  private final PlayStateService playStateService;

  public UserFavoriteItemsController(ItemService itemService, PlayStateService playStateService) {
    this.itemService = itemService;
    this.playStateService = playStateService;
  }

  @Transactional
  @PostMapping("/{itemId}")
  public ResponseEntity<Void> markFavorite(
      @PathVariable String itemId, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    UUID itemUuid = UuidUtils.parseUuid(itemId);
    if (itemUuid == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item ID");
    }

    UUID userUuid = authenticatedUser.getUserEntity().getId();

    if (itemService.findById(itemUuid).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
    }

    playStateService.setFavorite(userUuid, itemUuid, true);
    return ResponseEntity.ok().build();
  }

  @Transactional
  @DeleteMapping("/{itemId}")
  public ResponseEntity<Void> unmarkFavorite(
      @PathVariable String itemId, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {

    UUID itemUuid = UuidUtils.parseUuid(itemId);
    if (itemUuid == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item ID");
    }

    UUID userUuid = authenticatedUser.getUserEntity().getId();

    if (itemService.findById(itemUuid).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found");
    }

    playStateService.setFavorite(userUuid, itemUuid, false);
    return ResponseEntity.ok().build();
  }
}
