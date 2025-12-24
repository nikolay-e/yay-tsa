package com.example.mediaserver.infra.persistence.repository;

import com.example.mediaserver.infra.persistence.entity.ItemEntity;
import com.example.mediaserver.infra.persistence.entity.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ItemRepository extends JpaRepository<ItemEntity, UUID>, JpaSpecificationExecutor<ItemEntity> {
    Optional<ItemEntity> findByPath(String path);
    List<ItemEntity> findAllByParentId(UUID parentId);
    long countByType(ItemType type);

    @Query("SELECT i FROM ItemEntity i WHERE i.type = :type AND LOWER(i.name) = LOWER(:name)")
    Optional<ItemEntity> findByTypeAndNameIgnoreCase(@Param("type") ItemType type, @Param("name") String name);

    List<ItemEntity> findByLibraryRoot(String libraryRoot);
}
