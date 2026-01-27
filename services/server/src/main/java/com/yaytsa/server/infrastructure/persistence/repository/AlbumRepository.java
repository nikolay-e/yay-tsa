package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.AlbumEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
  List<AlbumEntity> findByArtistId(UUID artistId);

  @Query("SELECT a FROM AlbumEntity a WHERE a.artist.id = :artistId ORDER BY a.item.sortName ASC")
  List<AlbumEntity> findByAlbumArtistIdOrderBySortNameAsc(@Param("artistId") UUID artistId);

  @Query(
      "SELECT a FROM AlbumEntity a LEFT JOIN FETCH a.artist LEFT JOIN FETCH a.item WHERE a.itemId ="
          + " :itemId")
  AlbumEntity findByIdWithArtist(@Param("itemId") UUID itemId);

  @Query("SELECT COUNT(a) FROM AlbumEntity a WHERE a.artist.id = :artistId")
  long countByArtistId(@Param("artistId") UUID artistId);

  @Query(
      "SELECT a FROM AlbumEntity a "
          + "LEFT JOIN FETCH a.artist "
          + "LEFT JOIN FETCH a.item "
          + "WHERE a.itemId IN :itemIds")
  List<AlbumEntity> findAllByIdInWithArtist(@Param("itemIds") Collection<UUID> itemIds);
}
