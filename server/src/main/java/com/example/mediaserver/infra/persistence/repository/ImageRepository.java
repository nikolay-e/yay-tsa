package com.example.mediaserver.infra.persistence.repository;

import com.example.mediaserver.infra.persistence.entity.ImageEntity;
import com.example.mediaserver.infra.persistence.entity.ImageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImageRepository extends JpaRepository<ImageEntity, UUID> {
    Optional<ImageEntity> findByItemIdAndType(UUID itemId, ImageType type);
    List<ImageEntity> findAllByItemId(UUID itemId);
}
