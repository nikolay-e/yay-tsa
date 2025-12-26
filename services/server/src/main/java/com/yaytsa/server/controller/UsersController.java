package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ItemService;
import com.yaytsa.server.domain.service.PlayStateService;
import com.yaytsa.server.domain.service.UserService;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.dto.response.QueryResultResponse;
import com.yaytsa.server.dto.response.UserResponse;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.AlbumRepository;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.mapper.ItemMapper;
import com.yaytsa.server.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for user-specific item queries. These endpoints are prefixed with
 * /Users/{userId}/Items to provide user-scoped results.
 *
 * <p>IMPORTANT: The frontend uses these endpoints for: - Getting single items: GET
 * /Users/{userId}/Items/{itemId} - Listing playlists: GET
 * /Users/{userId}/Items?IncludeItemTypes=Playlist
 */
@RestController
@RequestMapping("/Users")
@Tag(name = "User Items", description = "User-specific item queries")
public class UsersController {

  private final UserService userService;
  private final ItemService itemService;
  private final PlayStateService playStateService;
  private final UserMapper userMapper;
  private final ItemMapper itemMapper;
  private final AudioTrackRepository audioTrackRepository;
  private final AlbumRepository albumRepository;

  public UsersController(
      UserService userService,
      ItemService itemService,
      PlayStateService playStateService,
      UserMapper userMapper,
      ItemMapper itemMapper,
      AudioTrackRepository audioTrackRepository,
      AlbumRepository albumRepository) {
    this.userService = userService;
    this.itemService = itemService;
    this.playStateService = playStateService;
    this.userMapper = userMapper;
    this.itemMapper = itemMapper;
    this.audioTrackRepository = audioTrackRepository;
    this.albumRepository = albumRepository;
  }

  @Operation(summary = "Get user by ID", description = "Retrieve user information by user ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved user"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping("/{userId}")
  public ResponseEntity<UserResponse> getUserById(
      @Parameter(description = "User ID") @PathVariable String userId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = UUID.fromString(userId);
    UserEntity user =
        userService
            .findById(userUuid)
            .orElseThrow(() -> new UserService.UserNotFoundException("User not found: " + userId));

    UserResponse userDto = userMapper.toDto(user);
    return ResponseEntity.ok(userDto);
  }

  @Operation(
      summary = "Get user items",
      description =
          "Query items scoped to a specific user. "
              + "Used for playlists, favorites, and user-specific filtering.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved items"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "User not found")
      })
  @GetMapping("/{userId}/Items")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getUserItems(
      @Parameter(description = "User ID") @PathVariable String userId,
      @Parameter(description = "Parent item ID") @RequestParam(required = false) String parentId,
      @Parameter(description = "Item types to include (e.g., Playlist, MusicAlbum)")
          @RequestParam(value = "IncludeItemTypes", required = false)
          String includeItemTypes,
      @Parameter(description = "Search recursively")
          @RequestParam(value = "Recursive", defaultValue = "false")
          boolean recursive,
      @Parameter(description = "Sort by field")
          @RequestParam(value = "SortBy", defaultValue = "SortName")
          String sortBy,
      @Parameter(description = "Sort order")
          @RequestParam(value = "SortOrder", defaultValue = "Ascending")
          String sortOrder,
      @Parameter(description = "Starting index")
          @RequestParam(value = "StartIndex", defaultValue = "0")
          int startIndex,
      @Parameter(description = "Limit results") @RequestParam(value = "Limit", defaultValue = "100")
          int limit,
      @Parameter(description = "Search term") @RequestParam(value = "SearchTerm", required = false)
          String searchTerm,
      @Parameter(description = "Filter for favorites only")
          @RequestParam(value = "IsFavorite", required = false)
          Boolean isFavorite,
      @Parameter(description = "Filter for played items")
          @RequestParam(value = "Filters", required = false)
          String filters,
      @Parameter(description = "Include user data in response")
          @RequestParam(value = "enableUserData", defaultValue = "true")
          boolean enableUserData,
      @Parameter(description = "Fields to include in response")
          @RequestParam(value = "Fields", required = false)
          String fields,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = UUID.fromString(userId);
    UUID parentUuid = parentId != null ? UUID.fromString(parentId) : null;

    List<String> itemTypesList =
        includeItemTypes != null ? Arrays.asList(includeItemTypes.split(",")) : null;

    ItemService.ItemsQueryParams params =
        new ItemService.ItemsQueryParams(
            userUuid,
            parentUuid,
            itemTypesList,
            recursive,
            sortBy,
            sortOrder,
            startIndex,
            limit,
            searchTerm,
            null,
            null,
            null,
            isFavorite);

    Page<ItemEntity> itemsPage = itemService.queryItems(params);

    List<BaseItemResponse> itemDtos =
        itemsPage.getContent().stream()
            .map(
                item -> {
                  PlayStateEntity playState =
                      enableUserData
                          ? playStateService.getPlayState(userUuid, item.getId()).orElse(null)
                          : null;

                  AudioTrackEntity audioTrack =
                      item.getType() == ItemType.AudioTrack
                          ? audioTrackRepository.findById(item.getId()).orElse(null)
                          : null;

                  AlbumEntity album = null;
                  if (audioTrack != null && audioTrack.getAlbum() != null) {
                    album = albumRepository.findByIdWithArtist(audioTrack.getAlbum().getId());
                  } else if (item.getType() == ItemType.MusicAlbum) {
                    album = albumRepository.findByIdWithArtist(item.getId());
                  }

                  return itemMapper.toDto(item, playState, audioTrack, album);
                })
            .collect(Collectors.toList());

    QueryResultResponse<BaseItemResponse> result =
        new QueryResultResponse<>(
            itemDtos, Math.toIntExact(itemsPage.getTotalElements()), startIndex);

    return ResponseEntity.ok(result);
  }

  @Operation(
      summary = "Get single item for user",
      description =
          "Retrieve a specific item with user-specific data (play state, favorite status, etc.)")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved item"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping("/{userId}/Items/{itemId}")
  public ResponseEntity<BaseItemResponse> getUserItem(
      @Parameter(description = "User ID") @PathVariable String userId,
      @Parameter(description = "Item ID") @PathVariable String itemId,
      @Parameter(description = "Fields to include")
          @RequestParam(value = "Fields", required = false)
          String fields,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = UUID.fromString(userId);
    UUID itemUuid = UUID.fromString(itemId);

    ItemEntity item =
        itemService
            .findById(itemUuid)
            .orElseThrow(() -> new RuntimeException("Item not found: " + itemId));

    PlayStateEntity playState = playStateService.getPlayState(userUuid, itemUuid).orElse(null);

    AudioTrackEntity audioTrack =
        item.getType() == ItemType.AudioTrack
            ? audioTrackRepository.findById(itemUuid).orElse(null)
            : null;

    AlbumEntity album = null;
    if (audioTrack != null && audioTrack.getAlbum() != null) {
      album = albumRepository.findByIdWithArtist(audioTrack.getAlbum().getId());
    } else if (item.getType() == ItemType.MusicAlbum) {
      album = albumRepository.findByIdWithArtist(itemUuid);
    }

    BaseItemResponse itemDto = itemMapper.toDto(item, playState, audioTrack, album);

    return ResponseEntity.ok(itemDto);
  }

  @Operation(
      summary = "Get user's favorite items",
      description = "Retrieve items marked as favorite by the user")
  @GetMapping("/{userId}/FavoriteItems")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getUserFavorites(
      @PathVariable String userId,
      @RequestParam(value = "IncludeItemTypes", required = false) String includeItemTypes,
      @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
      @RequestParam(value = "Limit", defaultValue = "100") int limit,
      @RequestParam(value = "Fields", required = false) String fields,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = UUID.fromString(userId);

    List<String> itemTypesList =
        includeItemTypes != null ? Arrays.asList(includeItemTypes.split(",")) : null;

    ItemService.ItemsQueryParams params =
        new ItemService.ItemsQueryParams(
            userUuid,
            null,
            itemTypesList,
            false,
            "SortName",
            "Ascending",
            startIndex,
            limit,
            null,
            null,
            null,
            null,
            true);

    Page<ItemEntity> itemsPage = itemService.queryItems(params);

    List<BaseItemResponse> itemDtos =
        itemsPage.getContent().stream()
            .map(
                item -> {
                  PlayStateEntity playState =
                      playStateService.getPlayState(userUuid, item.getId()).orElse(null);

                  AudioTrackEntity audioTrack =
                      item.getType() == ItemType.AudioTrack
                          ? audioTrackRepository.findById(item.getId()).orElse(null)
                          : null;

                  AlbumEntity album = null;
                  if (audioTrack != null && audioTrack.getAlbum() != null) {
                    album = albumRepository.findById(audioTrack.getAlbum().getId()).orElse(null);
                  } else if (item.getType() == ItemType.MusicAlbum) {
                    album = albumRepository.findById(item.getId()).orElse(null);
                  }

                  return itemMapper.toDto(item, playState, audioTrack, album);
                })
            .collect(Collectors.toList());

    QueryResultResponse<BaseItemResponse> result =
        new QueryResultResponse<>(
            itemDtos, Math.toIntExact(itemsPage.getTotalElements()), startIndex);

    return ResponseEntity.ok(result);
  }

  @Operation(
      summary = "Get user's recently played items",
      description = "Retrieve items recently played by the user, sorted by last played date")
  @GetMapping("/{userId}/Items/Resume")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getResumeItems(
      @PathVariable String userId,
      @RequestParam(value = "IncludeItemTypes", required = false) String includeItemTypes,
      @RequestParam(value = "Limit", defaultValue = "12") int limit,
      @RequestParam(value = "Fields", required = false) String fields,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = UUID.fromString(userId);

    List<String> itemTypesList =
        includeItemTypes != null ? Arrays.asList(includeItemTypes.split(",")) : null;

    ItemService.ItemsQueryParams params =
        new ItemService.ItemsQueryParams(
            userUuid,
            null,
            itemTypesList,
            false,
            "DatePlayed",
            "Descending",
            0,
            limit,
            null,
            null,
            null,
            null,
            null);

    Page<ItemEntity> itemsPage = itemService.queryItems(params);

    List<BaseItemResponse> itemDtos =
        itemsPage.getContent().stream()
            .map(
                item -> {
                  PlayStateEntity playState =
                      playStateService.getPlayState(userUuid, item.getId()).orElse(null);

                  AudioTrackEntity audioTrack =
                      item.getType() == ItemType.AudioTrack
                          ? audioTrackRepository.findById(item.getId()).orElse(null)
                          : null;

                  AlbumEntity album = null;
                  if (audioTrack != null && audioTrack.getAlbum() != null) {
                    album = albumRepository.findById(audioTrack.getAlbum().getId()).orElse(null);
                  } else if (item.getType() == ItemType.MusicAlbum) {
                    album = albumRepository.findById(item.getId()).orElse(null);
                  }

                  return itemMapper.toDto(item, playState, audioTrack, album);
                })
            .collect(Collectors.toList());

    QueryResultResponse<BaseItemResponse> result =
        new QueryResultResponse<>(itemDtos, Math.toIntExact(itemsPage.getTotalElements()), 0);

    return ResponseEntity.ok(result);
  }
}
