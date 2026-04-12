package com.yaytsa.server.controller;

import com.yaytsa.server.dto.response.QueryResultResponse;
import com.yaytsa.server.infrastructure.persistence.repository.GenreRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Transactional(readOnly = true)
public class GenresController {

  private final GenreRepository genreRepository;

  public GenresController(GenreRepository genreRepository) {
    this.genreRepository = genreRepository;
  }

  @GetMapping("/Genres")
  public ResponseEntity<QueryResultResponse<Map<String, Object>>> getGenres(
      @RequestParam(value = "StartIndex", defaultValue = "0") int startIndex,
      @RequestParam(value = "Limit", defaultValue = "100") int limit) {
    var genres = genreRepository.findAll();
    int total = genres.size();
    List<Map<String, Object>> items =
        genres.stream()
            .skip(startIndex)
            .limit(limit)
            .map(g -> Map.<String, Object>of("Name", g.getName(), "Id", g.getId().toString(), "Type", "Genre"))
            .toList();
    return ResponseEntity.ok(new QueryResultResponse<>(items, total, startIndex));
  }
}
