package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.PlayStateService;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.dto.response.QueryResultResponse;
import com.yaytsa.server.infrastructure.persistence.entity.ArtistEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlayStateEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ArtistRepository;
import com.yaytsa.server.mapper.ItemMapper;
import com.yaytsa.server.util.UuidUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/Artists")
@Transactional(readOnly = true)
public class ArtistsController {

  private final ArtistRepository artistRepository;
  private final PlayStateService playStateService;
  private final ItemMapper itemMapper;

  public ArtistsController(
      ArtistRepository artistRepository, PlayStateService playStateService, ItemMapper itemMapper) {
    this.artistRepository = artistRepository;
    this.playStateService = playStateService;
    this.itemMapper = itemMapper;
  }

  @GetMapping("/AlbumArtists")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getAlbumArtists(
      @RequestParam(value = "UserId", required = false) String userId,
      @RequestParam(value = "Filters", required = false) String filters,
      @RequestParam(value = "EnableUserData", defaultValue = "false") boolean enableUserData,
      @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
      @RequestParam(value = "Limit", defaultValue = "100") int limit) {

    UUID userUuid = userId != null ? UuidUtils.parseUuid(userId) : null;
    boolean filterFavorite = isFavoriteFilter(filters);

    List<ArtistEntity> artists = artistRepository.findAllByOrderByItem_SortNameAsc();

    List<UUID> artistItemIds = artists.stream().map(a -> a.getItem().getId()).toList();

    Map<UUID, PlayStateEntity> playStatesMap =
        (userUuid != null && (enableUserData || filterFavorite))
            ? playStateService.getPlayStatesForItems(userUuid, artistItemIds)
            : Map.of();

    if (filterFavorite) {
      artists =
          artists.stream()
              .filter(
                  a -> {
                    PlayStateEntity ps = playStatesMap.get(a.getItem().getId());
                    return ps != null && ps.getIsFavorite();
                  })
              .toList();
    }

    int total = artists.size();
    List<BaseItemResponse> items =
        artists.stream()
            .skip(startIndex)
            .limit(limit)
            .map(
                a ->
                    itemMapper.toDto(
                        a.getItem(),
                        playStatesMap.get(a.getItem().getId()),
                        null,
                        null,
                        null,
                        null))
            .toList();
    return ResponseEntity.ok(new QueryResultResponse<>(items, total, startIndex));
  }

  private static boolean isFavoriteFilter(String filters) {
    if (filters == null) return false;
    return Arrays.stream(filters.split(","))
        .map(String::trim)
        .anyMatch(f -> f.equalsIgnoreCase("IsFavorite"));
  }
}
