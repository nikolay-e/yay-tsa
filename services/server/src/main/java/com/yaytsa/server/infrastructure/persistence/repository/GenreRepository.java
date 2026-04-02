package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.GenreEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GenreRepository extends JpaRepository<GenreEntity, UUID> {
  Optional<GenreEntity> findByName(String name);

  @Query(
      value =
          """
          SELECT g.name FROM genres g
          JOIN item_genres ig ON ig.genre_id = g.id
          WHERE ig.item_id = :itemId
          """,
      nativeQuery = true)
  List<String> findGenreNamesByItemId(@Param("itemId") UUID itemId);
}
