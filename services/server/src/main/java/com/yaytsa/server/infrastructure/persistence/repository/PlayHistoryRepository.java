package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlayHistoryEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayHistoryRepository extends JpaRepository<PlayHistoryEntity, UUID> {

  @Query(
      "SELECT ph.item.id FROM PlayHistoryEntity ph "
          + "WHERE ph.user.id = :userId "
          + "ORDER BY ph.startedAt DESC")
  List<UUID> findRecentItemIdsByUser(@Param("userId") UUID userId, Pageable pageable);
}
