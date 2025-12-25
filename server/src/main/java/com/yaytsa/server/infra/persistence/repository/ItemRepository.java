package com.yaytsa.server.infra.persistence.repository;

import com.yaytsa.server.infra.persistence.entity.ItemEntity;
import com.yaytsa.server.infra.persistence.entity.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ItemRepository extends JpaRepository<ItemEntity, UUID>, JpaSpecificationExecutor<ItemEntity>, ItemRepositoryCustom {
    Optional<ItemEntity> findByPath(String path);
    List<ItemEntity> findAllByParentId(UUID parentId);
    long countByType(ItemType type);

    @Query("SELECT i FROM ItemEntity i WHERE i.type = :type AND LOWER(i.name) = LOWER(:name)")
    Optional<ItemEntity> findByTypeAndNameIgnoreCase(@Param("type") ItemType type, @Param("name") String name);

    List<ItemEntity> findByLibraryRoot(String libraryRoot);

    @Query("""
        SELECT i FROM ItemEntity i
        WHERE i.type = 'AudioTrack'
        AND i.libraryRoot = :libraryRoot
        AND i.path IS NOT NULL
        AND i.path NOT LIKE 'artist:%'
        AND i.path NOT LIKE 'album:%'
        """)
    List<ItemEntity> findAudioTracksByLibraryRoot(@Param("libraryRoot") String libraryRoot);

    @Query("""
        SELECT i FROM ItemEntity i
        JOIN PlayStateEntity ps ON ps.item = i
        WHERE ps.user.id = :userId
        AND ps.lastPlayedAt IS NOT NULL
        ORDER BY ps.lastPlayedAt DESC
        """)
    Page<ItemEntity> findRecentlyPlayedByUser(
        @Param("userId") UUID userId,
        Pageable pageable
    );
}
