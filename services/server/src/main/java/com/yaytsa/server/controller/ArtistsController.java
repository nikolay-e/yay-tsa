package com.yaytsa.server.controller;

import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.dto.response.QueryResultResponse;
import com.yaytsa.server.infrastructure.persistence.entity.ArtistEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ArtistRepository;
import com.yaytsa.server.mapper.ItemMapper;
import java.util.List;
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
  private final ItemMapper itemMapper;

  public ArtistsController(ArtistRepository artistRepository, ItemMapper itemMapper) {
    this.artistRepository = artistRepository;
    this.itemMapper = itemMapper;
  }

  @GetMapping("/AlbumArtists")
  public ResponseEntity<QueryResultResponse<BaseItemResponse>> getAlbumArtists(
      @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
      @RequestParam(value = "Limit", defaultValue = "100") int limit) {
    List<ArtistEntity> artists = artistRepository.findAllByOrderByItem_SortNameAsc();
    int total = artists.size();
    List<BaseItemResponse> items =
        artists.stream()
            .skip(startIndex)
            .limit(limit)
            .map(a -> itemMapper.toDto(a.getItem(), null, null, null, null, null))
            .toList();
    return ResponseEntity.ok(new QueryResultResponse<>(items, total, startIndex));
  }
}
