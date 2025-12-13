package com.example.mediaserver.controller;

import com.example.mediaserver.dto.response.BaseItemDto;
import com.example.mediaserver.dto.response.QueryResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for user-specific item queries.
 * These endpoints are prefixed with /Users/{userId}/Items to provide user-scoped results.
 *
 * IMPORTANT: The frontend uses these endpoints for:
 * - Getting single items: GET /Users/{userId}/Items/{itemId}
 * - Listing playlists: GET /Users/{userId}/Items?IncludeItemTypes=Playlist
 */
@RestController
@RequestMapping("/Users")
@Tag(name = "User Items", description = "User-specific item queries")
public class UsersController {

    @Operation(summary = "Get user items",
              description = "Query items scoped to a specific user. " +
                           "Used for playlists, favorites, and user-specific filtering.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved items"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{userId}/Items")
    public ResponseEntity<QueryResultDto<BaseItemDto>> getUserItems(
            @Parameter(description = "User ID") @PathVariable String userId,
            @Parameter(description = "Parent item ID") @RequestParam(required = false) String parentId,
            @Parameter(description = "Item types to include (e.g., Playlist, MusicAlbum)")
                @RequestParam(value = "IncludeItemTypes", required = false) String includeItemTypes,
            @Parameter(description = "Search recursively")
                @RequestParam(value = "Recursive", defaultValue = "false") boolean recursive,
            @Parameter(description = "Sort by field")
                @RequestParam(value = "SortBy", defaultValue = "SortName") String sortBy,
            @Parameter(description = "Sort order")
                @RequestParam(value = "SortOrder", defaultValue = "Ascending") String sortOrder,
            @Parameter(description = "Starting index")
                @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
            @Parameter(description = "Limit results")
                @RequestParam(value = "Limit", defaultValue = "100") int limit,
            @Parameter(description = "Search term")
                @RequestParam(value = "SearchTerm", required = false) String searchTerm,
            @Parameter(description = "Filter for favorites only")
                @RequestParam(value = "IsFavorite", required = false) Boolean isFavorite,
            @Parameter(description = "Filter for played items")
                @RequestParam(value = "Filters", required = false) String filters,
            @Parameter(description = "Include user data in response")
                @RequestParam(value = "enableUserData", defaultValue = "true") boolean enableUserData,
            @Parameter(description = "Fields to include in response")
                @RequestParam(value = "Fields", required = false) String fields,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 1 & 7 - Implement database queries with user-specific filtering
        // This endpoint is crucial for:
        // 1. Listing user's playlists (IncludeItemTypes=Playlist)
        // 2. Getting recently played (SortBy=DatePlayed, Filters=IsPlayed)
        // 3. Getting favorites (IsFavorite=true)

        QueryResultDto<BaseItemDto> result = QueryResultDto.empty();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get single item for user",
              description = "Retrieve a specific item with user-specific data (play state, favorite status, etc.)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved item"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/{userId}/Items/{itemId}")
    public ResponseEntity<BaseItemDto> getUserItem(
            @Parameter(description = "User ID") @PathVariable String userId,
            @Parameter(description = "Item ID") @PathVariable String itemId,
            @Parameter(description = "Fields to include")
                @RequestParam(value = "Fields", required = false) String fields,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 1 - Fetch item from database with user-specific data
        // This should return the item with UserData populated (play count, favorite status, etc.)

        // For now, return a stub audio track
        BaseItemDto item = BaseItemDto.audioTrack(
            itemId,
            "Sample Track",
            "Sample Track",
            UUID.randomUUID().toString(),
            "Sample Album",
            List.of(new BaseItemDto.NameGuidPair("Sample Artist", UUID.randomUUID().toString())),
            1,
            1,
            180000L, // 3 minutes in ms
            "mp3",
            Map.of("Primary", "sample-tag")
        );

        return ResponseEntity.ok(item);
    }

    @Operation(summary = "Get user's favorite items",
              description = "Retrieve items marked as favorite by the user")
    @GetMapping("/{userId}/FavoriteItems")
    public ResponseEntity<QueryResultDto<BaseItemDto>> getUserFavorites(
            @PathVariable String userId,
            @RequestParam(value = "IncludeItemTypes", required = false) String includeItemTypes,
            @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
            @RequestParam(value = "Limit", defaultValue = "100") int limit,
            @RequestParam(value = "Fields", required = false) String fields,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 6 - Implement favorites query
        QueryResultDto<BaseItemDto> result = QueryResultDto.empty();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get user's recently played items",
              description = "Retrieve items recently played by the user, sorted by last played date")
    @GetMapping("/{userId}/Items/Resume")
    public ResponseEntity<QueryResultDto<BaseItemDto>> getResumeItems(
            @PathVariable String userId,
            @RequestParam(value = "IncludeItemTypes", required = false) String includeItemTypes,
            @RequestParam(value = "Limit", defaultValue = "12") int limit,
            @RequestParam(value = "Fields", required = false) String fields,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "api_key", required = false) String apiKey) {

        // TODO: Phase 6 - Implement resume items query
        QueryResultDto<BaseItemDto> result = QueryResultDto.empty();
        return ResponseEntity.ok(result);
    }
}
