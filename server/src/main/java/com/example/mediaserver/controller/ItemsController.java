package com.example.mediaserver.controller;

import com.example.mediaserver.domain.service.ItemService;
import com.example.mediaserver.domain.service.PlayStateService;
import com.example.mediaserver.domain.service.PlaylistService;
import com.example.mediaserver.dto.BaseItemDto;
import com.example.mediaserver.dto.QueryResultDto;
import com.example.mediaserver.infra.persistence.entity.*;
import com.example.mediaserver.infra.persistence.repository.AlbumRepository;
import com.example.mediaserver.infra.persistence.repository.AudioTrackRepository;
import com.example.mediaserver.mapper.ItemMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/Items")
@Tag(name = "Items", description = "Media library item management")
public class ItemsController {

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
        AlbumRepository albumRepository
    ) {
        this.itemService = itemService;
        this.playStateService = playStateService;
        this.playlistService = playlistService;
        this.itemMapper = itemMapper;
        this.audioTrackRepository = audioTrackRepository;
        this.albumRepository = albumRepository;
    }

    @Operation(summary = "Get items from the library",
              description = "Query library items with filters, sorting, and pagination. " +
                           "Supports Jellyfin-compatible parameter names (PascalCase).")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved items"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping
    public ResponseEntity<QueryResultDto<BaseItemDto>> getItems(
            @Parameter(description = "User ID")
                @RequestParam(value = "userId", required = false) String userId,
            @Parameter(description = "Parent item ID (for folder filtering)")
                @RequestParam(value = "ParentId", required = false) String parentId,
            @Parameter(description = "Item types to include (e.g., MusicAlbum, Audio, MusicArtist)")
                @RequestParam(value = "IncludeItemTypes", required = false) String includeItemTypes,
            @Parameter(description = "Search recursively through folders")
                @RequestParam(value = "Recursive", defaultValue = "false") boolean recursive,
            @Parameter(description = "Sort by field (SortName, DateCreated, DatePlayed, Random, etc.)")
                @RequestParam(value = "SortBy", defaultValue = "SortName") String sortBy,
            @Parameter(description = "Sort order (Ascending or Descending)")
                @RequestParam(value = "SortOrder", defaultValue = "Ascending") String sortOrder,
            @Parameter(description = "Starting index for pagination")
                @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
            @Parameter(description = "Maximum number of results")
                @RequestParam(value = "Limit", defaultValue = "100") int limit,
            @Parameter(description = "Text search term")
                @RequestParam(value = "SearchTerm", required = false) String searchTerm,
            @Parameter(description = "Filter by artist IDs (comma-separated)")
                @RequestParam(value = "ArtistIds", required = false) String artistIds,
            @Parameter(description = "Filter by album IDs (comma-separated)")
                @RequestParam(value = "AlbumIds", required = false) String albumIds,
            @Parameter(description = "Filter by genre IDs (comma-separated)")
                @RequestParam(value = "GenreIds", required = false) String genreIds,
            @Parameter(description = "Filter for favorites only")
                @RequestParam(value = "IsFavorite", required = false) Boolean isFavorite,
            @Parameter(description = "Additional filters (e.g., IsPlayed)")
                @RequestParam(value = "Filters", required = false) String filters,
            @Parameter(description = "Include user-specific data (play count, favorite status)")
                @RequestParam(value = "enableUserData", defaultValue = "true") boolean enableUserData,
            @Parameter(description = "Fields to include in response (comma-separated)")
                @RequestParam(value = "Fields", required = false) String fields,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        UUID userUuid = userId != null ? UUID.fromString(userId) : null;
        UUID parentUuid = parentId != null ? UUID.fromString(parentId) : null;

        List<String> itemTypes = parseCommaSeparatedList(includeItemTypes);
        List<UUID> artistUuids = parseUuidList(artistIds);
        List<UUID> albumUuids = parseUuidList(albumIds);
        List<UUID> genreUuids = parseUuidList(genreIds);

        ItemService.ItemsQueryParams params = new ItemService.ItemsQueryParams(
            userUuid,
            parentUuid,
            itemTypes,
            recursive,
            sortBy,
            sortOrder,
            startIndex,
            limit,
            searchTerm,
            artistUuids,
            albumUuids,
            genreUuids,
            isFavorite
        );

        Page<ItemEntity> itemsPage = itemService.queryItems(params);

        List<BaseItemDto> dtos = itemsPage.getContent().stream()
            .map(item -> convertToDto(item, userUuid, enableUserData))
            .collect(Collectors.toList());

        QueryResultDto<BaseItemDto> result = new QueryResultDto<>(
            dtos,
            itemsPage.getTotalElements(),
            startIndex
        );

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get item by ID",
              description = "Retrieve a specific item by its ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved item"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/{itemId}")
    public ResponseEntity<BaseItemDto> getItem(
            @Parameter(description = "Item ID") @PathVariable String itemId,
            @Parameter(description = "User ID") @RequestParam(value = "userId", required = false) String userId,
            @Parameter(description = "Fields to include") @RequestParam(required = false) String fields,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        UUID itemUuid = UUID.fromString(itemId);
        UUID userUuid = userId != null ? UUID.fromString(userId) : null;

        Optional<ItemEntity> itemOpt = itemService.findById(itemUuid);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ItemEntity item = itemOpt.get();
        BaseItemDto dto = convertToDto(item, userUuid, true);

        return ResponseEntity.ok(dto);
    }

    @Operation(summary = "Get album tracks",
              description = "Retrieve all tracks for a specific album")
    @GetMapping("/{albumId}/Tracks")
    public ResponseEntity<QueryResultDto<BaseItemDto>> getAlbumTracks(
            @PathVariable String albumId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(defaultValue = "0") int startIndex,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        UUID albumUuid = UUID.fromString(albumId);
        UUID userUuid = userId != null ? UUID.fromString(userId) : null;

        List<AudioTrackEntity> tracks = itemService.getAlbumTracks(albumUuid);

        List<BaseItemDto> dtos = tracks.stream()
            .skip(startIndex)
            .limit(limit)
            .map(track -> {
                PlayStateEntity playState = null;
                if (userUuid != null) {
                    playState = playStateService.getPlayState(userUuid, track.getItemId()).orElse(null);
                }
                return itemMapper.toDto(track.getItem(), playState, track, null);
            })
            .collect(Collectors.toList());

        QueryResultDto<BaseItemDto> result = new QueryResultDto<>(
            dtos,
            tracks.size(),
            startIndex
        );

        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Mark item as favorite",
              description = "Add item to user's favorites")
    @PostMapping("/{itemId}/Favorite")
    public ResponseEntity<Void> markFavorite(
            @PathVariable String itemId,
            @RequestParam(value = "userId", required = true) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        UUID itemUuid = UUID.fromString(itemId);
        UUID userUuid = UUID.fromString(userId);

        playStateService.setFavorite(userUuid, itemUuid, true);

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Unmark item as favorite",
              description = "Remove item from user's favorites")
    @DeleteMapping("/{itemId}/Favorite")
    public ResponseEntity<Void> unmarkFavorite(
            @PathVariable String itemId,
            @RequestParam(value = "userId", required = true) String userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        UUID itemUuid = UUID.fromString(itemId);
        UUID userUuid = UUID.fromString(userId);

        playStateService.setFavorite(userUuid, itemUuid, false);

        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Delete item",
              description = "Delete an item (e.g., playlist). Requires appropriate permissions.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Item deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions")
    })
    @DeleteMapping("/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @Parameter(description = "Item ID to delete") @PathVariable String itemId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        UUID itemUuid = UUID.fromString(itemId);
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

    private List<UUID> parseUuidList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(UUID::fromString)
            .collect(Collectors.toList());
    }

    private BaseItemDto convertToDto(ItemEntity item, UUID userId, boolean enableUserData) {
        PlayStateEntity playState = null;
        if (userId != null && enableUserData) {
            playState = playStateService.getPlayState(userId, item.getId()).orElse(null);
        }

        AudioTrackEntity audioTrack = null;
        AlbumEntity album = null;

        if (item.getType() == ItemType.AudioTrack) {
            audioTrack = audioTrackRepository.findById(item.getId()).orElse(null);
        } else if (item.getType() == ItemType.MusicAlbum) {
            album = albumRepository.findById(item.getId()).orElse(null);
        }

        return itemMapper.toDto(item, playState, audioTrack, album);
    }
}
