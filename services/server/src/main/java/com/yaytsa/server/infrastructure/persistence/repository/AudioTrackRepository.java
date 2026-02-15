package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AudioTrackRepository extends JpaRepository<AudioTrackEntity, UUID> {
  List<AudioTrackEntity> findByAlbumId(UUID albumId);

  @Query(
      "SELECT at FROM AudioTrackEntity at LEFT JOIN FETCH at.item WHERE at.album.id = :albumId"
          + " ORDER BY at.discNumber ASC, at.trackNumber ASC")
  List<AudioTrackEntity> findByAlbumIdOrderByDiscNoAscTrackNoAsc(@Param("albumId") UUID albumId);

  List<AudioTrackEntity> findByAlbumArtistId(UUID artistId);

  @Query("SELECT COUNT(at) FROM AudioTrackEntity at WHERE at.album.id = :albumId")
  long countByAlbumId(@Param("albumId") UUID albumId);

  @Query(
      "SELECT at FROM AudioTrackEntity at "
          + "LEFT JOIN FETCH at.item "
          + "LEFT JOIN FETCH at.album "
          + "LEFT JOIN FETCH at.albumArtist "
          + "WHERE at.itemId IN :itemIds")
  List<AudioTrackEntity> findAllByIdInWithRelations(@Param("itemIds") Collection<UUID> itemIds);

  @Query(
      "SELECT at FROM AudioTrackEntity at "
          + "LEFT JOIN FETCH at.item "
          + "LEFT JOIN FETCH at.album a "
          + "LEFT JOIN FETCH a.parent "
          + "LEFT JOIN FETCH at.albumArtist")
  List<AudioTrackEntity> findAllWithRelations();

  @Query(
      "SELECT at FROM AudioTrackEntity at "
          + "LEFT JOIN FETCH at.item "
          + "LEFT JOIN FETCH at.album a "
          + "LEFT JOIN FETCH a.parent "
          + "LEFT JOIN FETCH at.albumArtist "
          + "WHERE at.itemId = :itemId")
  Optional<AudioTrackEntity> findByIdWithRelations(@Param("itemId") UUID itemId);

  @Query("SELECT at FROM AudioTrackEntity at JOIN FETCH at.item WHERE at.codec IS NOT NULL")
  List<AudioTrackEntity> findAllWithCodec();
}
