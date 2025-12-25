package com.example.mediaserver.infra.persistence.repository;

import com.example.mediaserver.infra.persistence.entity.AlbumEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
    List<AlbumEntity> findByArtistId(UUID artistId);

    @Query("SELECT a FROM AlbumEntity a WHERE a.artist.id = :artistId ORDER BY a.item.sortName ASC")
    List<AlbumEntity> findByAlbumArtistIdOrderBySortNameAsc(@Param("artistId") UUID artistId);

    @Query("SELECT a FROM AlbumEntity a LEFT JOIN FETCH a.artist WHERE a.itemId = :itemId")
    AlbumEntity findByIdWithArtist(@Param("itemId") UUID itemId);
}
