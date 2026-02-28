package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.PlaybackSignalEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PlaybackSignalRepository extends JpaRepository<PlaybackSignalEntity, UUID> {
  List<PlaybackSignalEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);

  @Query(
      value =
          """
          SELECT ps.track_id, COUNT(*) AS play_count,
                 SUM(CASE WHEN ps.signal_type IN ('PLAY_COMPLETE', 'SKIP_LATE') THEN 1 ELSE 0 END) AS completions,
                 SUM(CASE WHEN ps.signal_type = 'SKIP_EARLY' THEN 1 ELSE 0 END) AS skips
          FROM playback_signal ps
          JOIN listening_session ls ON ls.id = ps.session_id
          WHERE ls.user_id = :userId
            AND ps.created_at > :since
          GROUP BY ps.track_id
          ORDER BY play_count DESC
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> getPlayCountsByUser(
      @Param("userId") UUID userId, @Param("since") OffsetDateTime since, @Param("lim") int limit);

  @Query(
      value =
          """
          SELECT COUNT(*) AS session_count,
                 AVG(session_duration_min) AS avg_session_min
          FROM (
            SELECT EXTRACT(EPOCH FROM (MAX(ps.created_at) - MIN(ps.created_at))) / 60.0 AS session_duration_min
            FROM playback_signal ps
            JOIN listening_session ls ON ls.id = ps.session_id
            WHERE ls.user_id = :userId
              AND ps.created_at > :since
            GROUP BY ps.session_id
            HAVING COUNT(*) > 1
          ) sessions
          """,
      nativeQuery = true)
  List<Object[]> getSessionStats(
      @Param("userId") UUID userId, @Param("since") OffsetDateTime since);

  @Query(
      value =
          """
          SELECT ar.name AS artist_name,
                 COUNT(*) AS play_count,
                 SUM(CASE WHEN ps.signal_type IN ('PLAY_COMPLETE', 'SKIP_LATE') THEN 1 ELSE 0 END) AS completions
          FROM playback_signal ps
          JOIN listening_session ls ON ls.id = ps.session_id
          JOIN items i ON i.id = ps.track_id AND i.type = 'AudioTrack'
          JOIN items al ON al.id = i.parent_id
          LEFT JOIN items ar ON ar.id = al.parent_id
          WHERE ls.user_id = :userId
            AND ps.created_at > :since
            AND ar.name IS NOT NULL
          GROUP BY ar.name
          ORDER BY play_count DESC
          LIMIT :lim
          """,
      nativeQuery = true)
  List<Object[]> getTopArtistsByUser(
      @Param("userId") UUID userId, @Param("since") OffsetDateTime since, @Param("lim") int limit);
}
