package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ItemService;
import com.yaytsa.server.domain.service.PlayStateService;
import com.yaytsa.server.domain.service.PlaylistService;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.dto.response.QueryResultResponse;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.entity.AlbumEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaylistEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AlbumRepository;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import com.yaytsa.server.mapper.ItemMapper;
import com.yaytsa.server.util.UuidUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Items")
@Tag(name = "Items", description = "Media library item management")
@Transactional(readOnly = true)
public class ItemsController {

  private static final int MAX_PAGE_SIZE = 1000;

  private final ItemService itemService;
  private final PlayStateService playStateService;
  private final PlaylistService playlistService;
  private final ItemMapper itemMapper;
  private final AudioTrackRepository audioTrackRepository;
  private final AlbumRepository albumRepository;

  public ItemsController(
      ItemService itemService,
      PlayStateService playStateService,
      PlaylistService playlistService,
      ItemMapper itemMapper,
      AudioTrackRepository audioTrackRepository,
      AlbumRepository albumRepository) {
    this.itemService = itemService;
    this.playStateService = playStateService;
    this.playlistService = playlistService;
    this.itemMapper = itemMapper;
    this.audioTrackRepository = audioTrackRepository;
    this.albumRepository = albumRepository;
  }

  @Operation(
      summary = "Get items from the library",
      description =
          "Query library items with filters, sorting, and pagination. "
              + "Supports Jellyfin-compatible parameter names (PascalCase).")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved items"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
      })
  @GetMapping
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getItems(
      @Parameter(description = "User ID") @RequestParam(value = "userId", required = false)
          String userId,
      @Parameter(description = "Parent item ID (for folder filtering)")
          @RequestParam(value = "ParentId", required = false)
          String parentId,
      @Parameter(description = "Item types to include (e.g., MusicAlbum, Audio, MusicArtist)")
          @RequestParam(value = "IncludeItemTypes", required = false)
          String includeItemTypes,
      @Parameter(description = "Search recursively through folders")
          @RequestParam(value = "Recursive", defaultValue = "false")
          boolean recursive,
      @Parameter(description = "Sort by field (SortName, DateCreated, DatePlayed, Random, etc.)")
          @RequestParam(value = "SortBy", defaultValue = "SortName")
          String sortBy,
      @Parameter(description = "Sort order (Ascending or Descending)")
          @RequestParam(value = "SortOrder", defaultValue = "Ascending")
          String sortOrder,
      @Parameter(description = "Starting index for pagination")
          @RequestParam(value = "StartIndex", defaultValue = "0")
          int startIndex,
      @Parameter(description = "Maximum number of results")
          @RequestParam(value = "Limit", defaultValue = "100")
          int limit,
      @Parameter(description = "Text search term")
          @RequestParam(value = "SearchTerm", required = false)
          String searchTerm,
      @Parameter(description = "Filter by artist IDs (comma-separated)")
          @RequestParam(value = "ArtistIds", required = false)
          String artistIds,
      @Parameter(description = "Filter by album IDs (comma-separated)")
          @RequestParam(value = "AlbumIds", required = false)
          String albumIds,
      @Parameter(description = "Filter by genre IDs (comma-separated)")
          @RequestParam(value = "GenreIds", required = false)
          String genreIds,
      @Parameter(description = "Filter for favorites only")
          @RequestParam(value = "IsFavorite", required = false)
          Boolean isFavorite,
      @Parameter(description = "Comma-separated item IDs for batch fetch")
          @RequestParam(value = "Ids", required = false)
          String ids,
      @Parameter(description = "Additional filters (e.g., IsPlayed)")
          @RequestParam(value = "Filters", required = false)
          String filters,
      @Parameter(description = "Include user-specific data (play count, favorite status)")
          @RequestParam(value = "enableUserData", defaultValue = "true")
          boolean enableUserData,
      @Parameter(description = "Fields to include in response (comma-separated)")
          @RequestParam(value = "Fields", required = false)
          String fields,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userUuid = userId != null ? UuidUtils.parseUuid(userId) : null;
    if (userId != null && userUuid == null) {
      return ResponseEntity.badRequest().build();
    }
    if (userUuid != null && !isOwnerOrAdmin(userUuid, authenticatedUser)) {
      return ResponseEntity.status(403).build();
    }

    if (ids != null && !ids.isBlank()) {
      List<UUID> idList = UuidUtils.parseUuidList(ids);
      if (idList == null) {
        return ResponseEntity.badRequest().build();
      }
      if (idList.size() > MAX_PAGE_SIZE) {
        return ResponseEntity.badRequest().build();
      }
      List<ItemEntity> items = itemService.findAllByIds(idList);
      Map<UUID, ItemEntity> itemMap =
          items.stream().collect(Collectors.toMap(ItemEntity::getId, i -> i));
      List<BaseItemResponse> dtos =
          idList.stream()
              .map(itemMap::get)
              .filter(Objects::nonNull)
              .map(item -> convertToDto(item, userUuid, enableUserData))
              .collect(Collectors.toList());
      return ResponseEntity.ok(new QueryResultResponse<>(dtos, dtos.size(), 0));
    }

    UUID parentUuid = parentId != null ? UuidUtils.parseUuid(parentId) : null;
    if (parentId != null && parentUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    List<String> itemTypes = parseCommaSeparatedList(includeItemTypes);
    List<UUID> artistUuids = UuidUtils.parseUuidList(artistIds);
    List<UUID> albumUuids = UuidUtils.parseUuidList(albumIds);
    List<UUID> genreUuids = UuidUtils.parseUuidList(genreIds);

    if (artistUuids == null || albumUuids == null || genreUuids == null) {
      return ResponseEntity.badRequest().build();
    }

    int validLimit = Math.max(0, Math.min(limit, MAX_PAGE_SIZE));

    ItemService.ItemsQueryParams params =
        new ItemService.ItemsQueryParams(
            userUuid,
            parentUuid,
            itemTypes,
            recursive,
            sortBy,
            sortOrder,
            startIndex,
            validLimit,
            searchTerm,
            artistUuids,
            albumUuids,
            genreUuids,
            isFavorite);

    Page<ItemEntity> itemsPage = itemService.queryItems(params);
    List<ItemEntity> items = itemsPage.getContent();

    // Batch-load all secondary data to avoid N+1 queries
    List<UUID> itemIds = items.stream().map(ItemEntity::getId).toList();

    Map<UUID, PlayStateEntity> playStateMap =
        (userUuid != null && enableUserData && !itemIds.isEmpty())
            ? playStateService.getPlayStatesForItems(userUuid, itemIds)
            : Collections.emptyMap();

    List<UUID> trackIds = items.stream()
        .filter(i -> i.getType() == ItemType.AudioTrack).map(ItemEntity::getId).toList();
    Map<UUID, AudioTrackEntity> audioTrackMap = trackIds.isEmpty()
        ? Collections.emptyMap()
        : audioTrackRepository.findAllByIdInWithRelations(trackIds).stream()
            .collect(Collectors.toMap(at -> at.getItemId(), at -> at));

    List<UUID> albumItemIds = items.stream()
        .filter(i -> i.getType() == ItemType.MusicAlbum).map(ItemEntity::getId).toList();
    Map<UUID, AlbumEntity> albumMap = albumItemIds.isEmpty()
        ? Collections.emptyMap()
        : albumRepository.findAllByIdInWithArtist(albumItemIds).stream()
            .collect(Collectors.toMap(a -> a.getItemId(), a -> a));

    Map<UUID, Long> trackCountByAlbum = albumItemIds.isEmpty()
        ? Collections.emptyMap()
        : audioTrackRepository.countByAlbumIdIn(albumItemIds).stream()
            .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));

    List<UUID> artistIds2 = items.stream()
        .filter(i -> i.getType() == ItemType.MusicArtist).map(ItemEntity::getId).toList();
    Map<UUID, Long> albumCountByArtist = artistIds2.isEmpty()
        ? Collections.emptyMap()
        : albumRepository.countByArtistIdIn(artistIds2).stream()
            .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));

    List<BaseItemResponse> dtos = items.stream()
        .map(item -> convertToDto(
            item,
            playStateMap.get(item.getId()),
            audioTrackMap.get(item.getId()),
            albumMap.get(item.getId()),
            trackCountByAlbum.get(item.getId()),
            albumCountByArtist.get(item.getId())))
        .collect(Collectors.toList());

    QueryResultResponse<BaseItemResponse> result =
        new QueryResultResponse<>(dtos, Math.toIntExact(itemsPage.getTotalElements()), startIndex);

    return ResponseEntity.ok(result);
  }

  @Operation(summary = "Get item by ID", description = "Retrieve a specific item by its ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved item"),
        @ApiResponse(responseCode = "400", description = "Invalid ID format"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @GetMapping("/{itemId}")
  public ResponseEntity<BaseItemResponse> getItem(
      @Parameter(description = "Item ID") @PathVariable String itemId,
      @Parameter(description = "User ID") @RequestParam(value = "userId", required = false)
          String userId,
      @Parameter(description = "Fields to include") @RequestParam(required = false) String fields,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID itemUuid = UuidUtils.parseUuid(itemId);
    if (itemUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    UUID userUuid = userId != null ? UuidUtils.parseUuid(userId) : null;
    if (userId != null && userUuid == null) {
      return ResponseEntity.badRequest().build();
    }
    if (userUuid != null && !isOwnerOrAdmin(userUuid, authenticatedUser)) {
      return ResponseEntity.status(403).build();
    }

    Optional<ItemEntity> itemOpt = itemService.findById(itemUuid);
    if (itemOpt.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    ItemEntity item = itemOpt.get();
    BaseItemResponse dto = convertToDto(item, userUuid, true);

    return ResponseEntity.ok(dto);
  }

  @Operation(summary = "Get album tracks", description = "Retrieve all tracks for a specific album")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved tracks"),
        @ApiResponse(responseCode = "400", description = "Invalid ID format")
      })
  @GetMapping("/{albumId}/Tracks")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getAlbumTracks(
      @PathVariable String albumId,
      @RequestParam(value = "userId", required = false) String userId,
      @RequestParam(defaultValue = "0") int startIndex,
      @RequestParam(defaultValue = "100") int limit,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID albumUuid = UuidUtils.parseUuid(albumId);
    if (albumUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    UUID userUuid = userId != null ? UuidUtils.parseUuid(userId) : null;
    if (userId != null && userUuid == null) {
      return ResponseEntity.badRequest().build();
    }
    if (userUuid != null && !isOwnerOrAdmin(userUuid, authenticatedUser)) {
      return ResponseEntity.status(403).build();
    }

    List<AudioTrackEntity> tracks = itemService.getAlbumTracks(albumUuid);

    List<BaseItemResponse> dtos =
        tracks.stream()
            .skip(startIndex)
            .limit(limit)
            .map(
                track -> {
                  PlayStateEntity playState = null;
                  if (userUuid != null) {
                    playState =
                        playStateService.getPlayState(userUuid, track.getItemId()).orElse(null);
                  }
                  return itemMapper.toDto(track.getItem(), playState, track, null);
                })
            .collect(Collectors.toList());

    QueryResultResponse<BaseItemResponse> result =
        new QueryResultResponse<>(dtos, tracks.size(), startIndex);

    return ResponseEntity.ok(result);
  }

  @Operation(summary = "Mark item as favorite", description = "Add item to user's favorites")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully marked as favorite"),
        @ApiResponse(responseCode = "400", description = "Invalid ID format")
      })
  @Transactional
  @PostMapping("/{itemId}/Favorite")
  public ResponseEntity<Void> markFavorite(
      @PathVariable String itemId,
      @RequestParam(value = "userId", required = true) String userId,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID itemUuid = UuidUtils.parseUuid(itemId);
    if (itemUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    UUID userUuid = UuidUtils.parseUuid(userId);
    if (userUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    if (!isOwnerOrAdmin(userUuid, authenticatedUser)) {
      return ResponseEntity.status(403).build();
    }

    playStateService.setFavorite(userUuid, itemUuid, true);

    return ResponseEntity.ok().build();
  }

  @Operation(summary = "Unmark item as favorite", description = "Remove item from user's favorites")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Successfully unmarked as favorite"),
        @ApiResponse(responseCode = "400", description = "Invalid ID format")
      })
  @Transactional
  @DeleteMapping("/{itemId}/Favorite")
  public ResponseEntity<Void> unmarkFavorite(
      @PathVariable String itemId,
      @RequestParam(value = "userId", required = true) String userId,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID itemUuid = UuidUtils.parseUuid(itemId);
    if (itemUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    UUID userUuid = UuidUtils.parseUuid(userId);
    if (userUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    if (!isOwnerOrAdmin(userUuid, authenticatedUser)) {
      return ResponseEntity.status(403).build();
    }

    playStateService.setFavorite(userUuid, itemUuid, false);

    return ResponseEntity.ok().build();
  }

  @Operation(
      summary = "Delete item",
      description =
          "Delete an item (only playlists can be deleted). Requires appropriate permissions.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Item deleted successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid item ID format"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(
            responseCode = "403",
            description = "Forbidden - only playlists can be deleted")
      })
  @Transactional
  @DeleteMapping("/{itemId}")
  public ResponseEntity<Void> deleteItem(
      @Parameter(description = "Item ID to delete") @PathVariable String itemId,
      @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID itemUuid = UuidUtils.parseUuid(itemId);
    if (itemUuid == null) {
      return ResponseEntity.badRequest().build();
    }

    Optional<ItemEntity> itemOptional = itemService.findById(itemUuid);
    if (itemOptional.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    ItemEntity item = itemOptional.get();
    if (item.getType() != ItemType.Playlist) {
      return ResponseEntity.status(403).build();
    }

    Optional<PlaylistEntity> playlist = playlistService.getPlaylist(itemUuid);
    if (playlist.isPresent() && !isOwnerOrAdmin(playlist.get().getUserId(), authenticatedUser)) {
      return ResponseEntity.status(403).build();
    }

    playlistService.deletePlaylist(itemUuid);
    return ResponseEntity.noContent().build();
  }

  private List<String> parseCommaSeparatedList(String value) {
    if (value == null || value.isBlank()) {
      return Collections.emptyList();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  private boolean isOwnerOrAdmin(UUID resourceOwnerId, AuthenticatedUser user) {
    if (user == null) {
      return false;
    }
    if (user.getUserEntity().isAdmin()) {
      return true;
    }
    return resourceOwnerId.equals(user.getUserEntity().getId());
  }

  private BaseItemResponse convertToDto(
      ItemEntity item,
      PlayStateEntity playState,
      AudioTrackEntity audioTrack,
      AlbumEntity album,
      Long trackCount,
      Long albumCount) {
    Integer childCount = null;
    if (item.getType() == ItemType.MusicAlbum && trackCount != null) {
      childCount = trackCount.intValue();
    } else if (item.getType() == ItemType.MusicArtist && albumCount != null) {
      childCount = albumCount.intValue();
    }
    return itemMapper.toDto(item, playState, audioTrack, album, childCount);
  }

  private BaseItemResponse convertToDto(ItemEntity item, UUID userId, boolean enableUserData) {
    PlayStateEntity playState = null;
    if (userId != null && enableUserData) {
      playState = playStateService.getPlayState(userId, item.getId()).orElse(null);
    }

    AudioTrackEntity audioTrack = null;
    AlbumEntity album = null;
    Integer childCount = null;

    if (item.getType() == ItemType.AudioTrack) {
      audioTrack = audioTrackRepository.findById(item.getId()).orElse(null);
    } else if (item.getType() == ItemType.MusicAlbum) {
      album = albumRepository.findByIdWithArtist(item.getId());
      childCount = (int) audioTrackRepository.countByAlbumId(item.getId());
    } else if (item.getType() == ItemType.MusicArtist) {
      childCount = (int) albumRepository.countByArtistId(item.getId());
    }

    return itemMapper.toDto(item, playState, audioTrack, album, childCount);
  }
}
