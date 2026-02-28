package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlayHistoryEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayHistoryRepository extends JpaRepository<PlayHistoryEntity, UUID> {

  @Query(
      value =
          """
          SELECT ph.item_id
          FROM play_history ph
          WHERE ph.user_id = :userId
            AND ph.started_at >= :since
          GROUP BY ph.item_id
          HAVING COUNT(*) >= 3
          ORDER BY COUNT(*) DESC
          """,
      nativeQuery = true)
  List<UUID> findOverplayedTrackIds(
      @Param("userId") UUID userId, @Param("since") OffsetDateTime since);
}
