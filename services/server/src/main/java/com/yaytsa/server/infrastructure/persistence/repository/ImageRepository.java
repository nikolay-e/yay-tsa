package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ImageEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ImageType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ImageRepository extends JpaRepository<ImageEntity, UUID> {
  Optional<ImageEntity> findFirstByItem_IdAndType(UUID itemId, ImageType type);

  List<ImageEntity> findAllByItem_Id(UUID itemId);
}
