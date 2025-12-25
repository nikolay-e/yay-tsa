package com.yaytsa.server.infra.persistence.repository;

import com.yaytsa.server.infra.persistence.entity.ImageEntity;
import com.yaytsa.server.infra.persistence.entity.ImageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImageRepository extends JpaRepository<ImageEntity, UUID> {
    Optional<ImageEntity> findFirstByItemIdAndType(UUID itemId, ImageType type);
    List<ImageEntity> findAllByItemId(UUID itemId);
}
