package com.example.mediaserver.infra.persistence.repository;

import com.example.mediaserver.infra.persistence.entity.PlayStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlayStateRepository extends JpaRepository<PlayStateEntity, UUID> {
    Optional<PlayStateEntity> findByUserIdAndItemId(UUID userId, UUID itemId);
    List<PlayStateEntity> findAllByUserIdAndIsFavoriteTrue(UUID userId);

    @Query("SELECT ps FROM PlayStateEntity ps WHERE ps.user.id = :userId AND ps.item.id IN :itemIds")
    List<PlayStateEntity> findAllByUserIdAndItemIdIn(@Param("userId") UUID userId, @Param("itemIds") Collection<UUID> itemIds);
}
