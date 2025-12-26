package com.yaytsa.server.infra.persistence.repository;

import com.yaytsa.server.infra.persistence.entity.AudioTrackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AudioTrackRepository extends JpaRepository<AudioTrackEntity, UUID> {
    List<AudioTrackEntity> findByAlbumId(UUID albumId);

    @Query("SELECT at FROM AudioTrackEntity at LEFT JOIN FETCH at.item WHERE at.album.id = :albumId ORDER BY at.discNumber ASC, at.trackNumber ASC")
    List<AudioTrackEntity> findByAlbumIdOrderByDiscNoAscTrackNoAsc(@Param("albumId") UUID albumId);

    List<AudioTrackEntity> findByAlbumArtistId(UUID artistId);

    @Query("SELECT COUNT(at) FROM AudioTrackEntity at WHERE at.album.id = :albumId")
    long countByAlbumId(@Param("albumId") UUID albumId);
}
