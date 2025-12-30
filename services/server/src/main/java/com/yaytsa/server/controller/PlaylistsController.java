package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ItemService;
import com.yaytsa.server.domain.service.PlaylistService;
import com.yaytsa.server.dto.request.CreatePlaylistRequest;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaylistEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaylistEntryEntity;
import com.yaytsa.server.mapper.ItemMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/Playlists")
@Tag(name = "Playlists", description = "Playlist management")
public class PlaylistsController {

  private final PlaylistService playlistService;
  private final ItemService itemService;
  private final ItemMapper itemMapper;

  public PlaylistsController(
      PlaylistService playlistService, ItemService itemService, ItemMapper itemMapper) {
    this.playlistService = playlistService;
    this.itemService = itemService;
    this.itemMapper = itemMapper;
  }

  @Operation(
      summary = "Get user playlists",
      description = "Retrieve all playlists for the current user")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getPlaylists(
      @Parameter(description = "User ID") @RequestParam(required = false) String userId,
      @Parameter(description = "Starting index") @RequestParam(defaultValue = "0") int startIndex,
      @Parameter(description = "Limit results") @RequestParam(defaultValue = "100") int limit,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    List<Map<String, Object>> items = new ArrayList<>();

    if (userId != null) {
      List<PlaylistEntity> playlists = playlistService.getUserPlaylists(UUID.fromString(userId));

      int endIndex = Math.min(startIndex + limit, playlists.size());
      List<PlaylistEntity> paginatedPlaylists =
          playlists.subList(Math.min(startIndex, playlists.size()), endIndex);

      for (PlaylistEntity playlist : paginatedPlaylists) {
        Map<String, Object> item = new HashMap<>();
        item.put("Id", playlist.getId().toString());
        item.put("Name", playlist.getName());
        item.put("Type", "Playlist");
        item.put("IsFolder", true);
        item.put("ChildCount", playlistService.getPlaylistItemCount(playlist.getId()));
        items.add(item);
      }
    }

    Map<String, Object> response = new HashMap<>();
    response.put("Items", items);
    response.put("TotalRecordCount", items.size());
    response.put("StartIndex", startIndex);

    return ResponseEntity.ok(response);
  }

  @Operation(summary = "Create new playlist", description = "Create a new playlist for the user")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Playlist created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping
  public ResponseEntity<Map<String, String>> createPlaylist(
      @RequestBody CreatePlaylistRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID userId = UUID.fromString(request.userId());
    List<UUID> itemIds =
        request.ids() != null
            ? request.ids().stream().map(UUID::fromString).collect(Collectors.toList())
            : null;

    PlaylistEntity playlist = playlistService.createPlaylist(userId, request.name(), itemIds);

    Map<String, String> response = new HashMap<>();
    response.put("Id", playlist.getId().toString());

    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @Operation(summary = "Get playlist by ID", description = "Retrieve a specific playlist")
  @GetMapping("/{playlistId}")
  public ResponseEntity<Map<String, Object>> getPlaylist(
      @PathVariable String playlistId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    Optional<PlaylistEntity> optionalPlaylist =
        playlistService.getPlaylist(UUID.fromString(playlistId));

    if (optionalPlaylist.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    PlaylistEntity playlistEntity = optionalPlaylist.get();
    Map<String, Object> playlist = new HashMap<>();
    playlist.put("Id", playlistEntity.getId().toString());
    playlist.put("Name", playlistEntity.getName());
    playlist.put("Type", "Playlist");
    playlist.put("IsFolder", true);
    playlist.put("ChildCount", playlistService.getPlaylistItemCount(playlistEntity.getId()));
    playlist.put("UserId", playlistEntity.getUserId().toString());

    return ResponseEntity.ok(playlist);
  }

  @Operation(summary = "Update playlist", description = "Update playlist metadata")
  @PutMapping("/{playlistId}")
  public ResponseEntity<Map<String, Object>> updatePlaylist(
      @PathVariable String playlistId,
      @RequestBody Map<String, Object> playlistData,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    String name = playlistData.get("Name") != null ? playlistData.get("Name").toString() : null;
    Optional<PlaylistEntity> updated =
        playlistService.updatePlaylist(UUID.fromString(playlistId), name);

    if (updated.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    PlaylistEntity playlistEntity = updated.get();
    Map<String, Object> playlist = new HashMap<>();
    playlist.put("Id", playlistEntity.getId().toString());
    playlist.put("Name", playlistEntity.getName());
    playlist.put("Type", "Playlist");

    return ResponseEntity.ok(playlist);
  }

  @Operation(summary = "Delete playlist", description = "Delete a playlist")
  @DeleteMapping("/{playlistId}")
  public ResponseEntity<Void> deletePlaylist(
      @PathVariable String playlistId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    playlistService.deletePlaylist(UUID.fromString(playlistId));
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Get playlist items", description = "Get all items in a playlist")
  @GetMapping("/{playlistId}/Items")
  public ResponseEntity<Map<String, Object>> getPlaylistItems(
      @PathVariable String playlistId,
      @Parameter(description = "User ID for user-specific data")
          @RequestParam(value = "UserId", required = false)
          String userId,
      @Parameter(description = "Starting index for pagination")
          @RequestParam(value = "StartIndex", defaultValue = "0")
          int startIndex,
      @Parameter(description = "Maximum number of results")
          @RequestParam(value = "Limit", defaultValue = "100")
          int limit,
      @Parameter(description = "Fields to include in response")
          @RequestParam(value = "Fields", required = false)
          String fields,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID playlistUuid = UUID.fromString(playlistId);
    Page<PlaylistEntryEntity> page =
        playlistService.getPlaylistItems(playlistUuid, startIndex, limit);

    List<Map<String, Object>> items =
        page.getContent().stream()
            .map(
                entry -> {
                  Map<String, Object> item = new HashMap<>();
                  item.put("PlaylistItemId", entry.getId().toString());

                  Optional<ItemEntity> itemEntity = itemService.findById(entry.getItemId());
                  if (itemEntity.isPresent()) {
                    BaseItemResponse dto = itemMapper.toDto(itemEntity.get(), null);
                    item.put("Id", dto.id());
                    item.put("Name", dto.name());
                    item.put("Type", dto.type());
                    item.put("RunTimeTicks", dto.runTimeTicks());
                    item.put("IndexNumber", dto.indexNumber());
                    item.put("ParentIndexNumber", dto.parentIndexNumber());
                    item.put("AlbumId", dto.albumId());
                    item.put("AlbumArtist", dto.albumArtist());
                    if (dto.imageTags() != null) {
                      item.put("ImageTags", dto.imageTags());
                    }
                  } else {
                    item.put("Id", entry.getItemId().toString());
                  }
                  return item;
                })
            .collect(Collectors.toList());

    Map<String, Object> response = new HashMap<>();
    response.put("Items", items);
    response.put("TotalRecordCount", Math.toIntExact(page.getTotalElements()));
    response.put("StartIndex", startIndex);

    return ResponseEntity.ok(response);
  }

  @Operation(
      summary = "Add items to playlist",
      description =
          "Add one or more items to a playlist. "
              + "Item IDs are passed as comma-separated values in the Ids parameter.")
  @PostMapping("/{playlistId}/Items")
  public ResponseEntity<Void> addItemsToPlaylist(
      @PathVariable String playlistId,
      @Parameter(description = "Item IDs to add (comma-separated)") @RequestParam(value = "Ids")
          String ids,
      @Parameter(description = "User ID") @RequestParam(value = "UserId", required = false)
          String userId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID playlistUuid = UUID.fromString(playlistId);
    List<UUID> itemIds =
        Arrays.stream(ids.split(","))
            .map(String::trim)
            .map(UUID::fromString)
            .collect(Collectors.toList());

    playlistService.addItemsToPlaylist(playlistUuid, itemIds);

    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Remove items from playlist",
      description =
          "Remove specific items from a playlist by their playlist entry IDs. "
              + "Note: These are playlist entry IDs, not the original item IDs.")
  @DeleteMapping("/{playlistId}/Items")
  public ResponseEntity<Void> removeItemsFromPlaylist(
      @PathVariable String playlistId,
      @Parameter(description = "Playlist entry IDs to remove (comma-separated)")
          @RequestParam(value = "EntryIds")
          String entryIds,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID playlistUuid = UUID.fromString(playlistId);
    List<UUID> entryIdsList =
        Arrays.stream(entryIds.split(","))
            .map(String::trim)
            .map(UUID::fromString)
            .collect(Collectors.toList());

    playlistService.removeItemsFromPlaylist(playlistUuid, entryIdsList);

    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Move playlist item",
      description = "Move an item to a new position in the playlist")
  @PostMapping("/{playlistId}/Items/{itemId}/Move/{newIndex}")
  public ResponseEntity<Void> movePlaylistItem(
      @PathVariable String playlistId,
      @PathVariable String itemId,
      @PathVariable int newIndex,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    UUID playlistUuid = UUID.fromString(playlistId);
    UUID entryId = UUID.fromString(itemId);

    playlistService.movePlaylistItem(playlistUuid, entryId, newIndex);

    return ResponseEntity.noContent().build();
  }
}
