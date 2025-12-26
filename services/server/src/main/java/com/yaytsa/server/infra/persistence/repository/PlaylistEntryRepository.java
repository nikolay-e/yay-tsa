package com.yaytsa.server.infra.persistence.repository;

import com.yaytsa.server.infra.persistence.entity.PlaylistEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlaylistEntryRepository extends JpaRepository<PlaylistEntryEntity, UUID> {
    List<PlaylistEntryEntity> findAllByPlaylistIdOrderByPosition(UUID playlistId);

    Page<PlaylistEntryEntity> findByPlaylistId(UUID playlistId, Pageable pageable);

    List<PlaylistEntryEntity> findByPlaylistIdOrderByPositionAsc(UUID playlistId);

    @Query("SELECT MAX(pe.position) FROM PlaylistEntryEntity pe WHERE pe.playlistId = :playlistId")
    Optional<Integer> findMaxPositionByPlaylistId(@Param("playlistId") UUID playlistId);

    @Modifying
    @Query("DELETE FROM PlaylistEntryEntity pe WHERE pe.playlistId = :playlistId")
    void deleteByPlaylistId(@Param("playlistId") UUID playlistId);

    void deleteByPlaylistIdAndId(UUID playlistId, UUID entryId);
}
