package com.yaytsa.server.infrastructure.persistence.repository;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ItemRepository
    extends JpaRepository<ItemEntity, UUID>,
        JpaSpecificationExecutor<ItemEntity>,
        ItemRepositoryCustom {
  Optional<ItemEntity> findByPath(String path);

  List<ItemEntity> findAllByParentId(UUID parentId);

  long countByType(ItemType type);

  @Query("SELECT i FROM ItemEntity i WHERE i.type = :type AND LOWER(i.name) = LOWER(:name)")
  Optional<ItemEntity> findByTypeAndNameIgnoreCase(
      @Param("type") ItemType type, @Param("name") String name);

  List<ItemEntity> findByLibraryRoot(String libraryRoot);

  @Query(
      """
      SELECT i FROM ItemEntity i
      WHERE i.type = 'AudioTrack'
      AND i.libraryRoot = :libraryRoot
      AND i.path IS NOT NULL
      AND i.path NOT LIKE 'artist:%'
      AND i.path NOT LIKE 'album:%'
      """)
  List<ItemEntity> findAudioTracksByLibraryRoot(@Param("libraryRoot") String libraryRoot);

  @Query(
      value =
          """
          SELECT i.* FROM items i
          LEFT JOIN play_state ps ON ps.item_id = i.id AND ps.user_id = :userId
          WHERE i.type = 'AudioTrack'
            AND i.path IS NOT NULL
            AND i.path NOT LIKE 'artist:%'
            AND i.path NOT LIKE 'album:%'
          ORDER BY
            CASE WHEN ps.last_played_at IS NOT NULL THEN 0 ELSE 1 END,
            ps.last_played_at DESC NULLS LAST,
            i.sort_name, i.id
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM items
          WHERE type = 'AudioTrack'
            AND path IS NOT NULL
            AND path NOT LIKE 'artist:%'
            AND path NOT LIKE 'album:%'
          """,
      nativeQuery = true)
  Page<ItemEntity> findRecentlyPlayedByUser(@Param("userId") UUID userId, Pageable pageable);

  @Query(
      value =
          """
          SELECT a.* FROM items a
          LEFT JOIN (
            SELECT at2.album_id, MAX(ps2.last_played_at) AS max_played_at
            FROM audio_tracks at2
            LEFT JOIN play_state ps2 ON ps2.item_id = at2.item_id AND ps2.user_id = :userId
            GROUP BY at2.album_id
          ) agg ON agg.album_id = a.id
          WHERE a.type = 'MusicAlbum'
            AND EXISTS (SELECT 1 FROM audio_tracks WHERE album_id = a.id)
          ORDER BY
            CASE WHEN agg.max_played_at IS NOT NULL THEN 0 ELSE 1 END,
            agg.max_played_at DESC NULLS LAST,
            a.sort_name, a.id
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM items a
          WHERE a.type = 'MusicAlbum'
            AND EXISTS (SELECT 1 FROM audio_tracks WHERE album_id = a.id)
          """,
      nativeQuery = true)
  Page<ItemEntity> findRecentlyPlayedAlbumsByUser(@Param("userId") UUID userId, Pageable pageable);

  @Query(
      value =
          """
          SELECT a.* FROM items a
          LEFT JOIN (
            SELECT at2.album_artist_id, MAX(ps2.last_played_at) AS max_played_at
            FROM audio_tracks at2
            LEFT JOIN play_state ps2 ON ps2.item_id = at2.item_id AND ps2.user_id = :userId
            WHERE at2.album_artist_id IS NOT NULL
            GROUP BY at2.album_artist_id
          ) agg ON agg.album_artist_id = a.id
          WHERE a.type = 'MusicArtist'
            AND EXISTS (
              SELECT 1 FROM items album
              JOIN audio_tracks at3 ON at3.album_id = album.id
              WHERE album.parent_id = a.id
            )
          ORDER BY
            CASE WHEN agg.max_played_at IS NOT NULL THEN 0 ELSE 1 END,
            agg.max_played_at DESC NULLS LAST,
            a.sort_name, a.id
          """,
      countQuery =
          """
          SELECT COUNT(*) FROM items a
          WHERE a.type = 'MusicArtist'
            AND EXISTS (
              SELECT 1 FROM items album
              JOIN audio_tracks at3 ON at3.album_id = album.id
              WHERE album.parent_id = a.id
            )
          """,
      nativeQuery = true)
  Page<ItemEntity> findRecentlyPlayedArtistsByUser(@Param("userId") UUID userId, Pageable pageable);

  @Query(
      """
      SELECT i FROM ItemEntity i
      WHERE i.type = 'MusicAlbum'
      AND NOT EXISTS (
        SELECT 1 FROM ImageEntity img
        WHERE img.item = i AND img.type = 'Primary'
      )
      """)
  List<ItemEntity> findAlbumsWithoutPrimaryImage();

  @Query(
      """
      SELECT i FROM ItemEntity i
      WHERE i.type = 'MusicArtist'
      AND NOT EXISTS (
        SELECT 1 FROM ImageEntity img
        WHERE img.item = i AND img.type = 'Primary'
      )
      """)
  List<ItemEntity> findArtistsWithoutPrimaryImage();

  @Query(
      value =
          """
          SELECT DISTINCT ON (track.parent_id) track.parent_id AS parent_id, track.path AS track_path
          FROM items track
          WHERE track.type = 'AudioTrack'
          AND track.parent_id IN (:parentIds)
          AND track.path IS NOT NULL
          AND track.path NOT LIKE 'artist:%'
          AND track.path NOT LIKE 'album:%'
          ORDER BY track.parent_id, track.path
          """,
      nativeQuery = true)
  List<Object[]> findFirstTrackPathPerParent(@Param("parentIds") List<UUID> parentIds);

  @Query(
      value =
          """
          SELECT DISTINCT ON (album.parent_id) album.parent_id AS artist_id, track.path AS track_path
          FROM items album
          JOIN items track ON track.parent_id = album.id AND track.type = 'AudioTrack'
          WHERE album.type = 'MusicAlbum'
          AND album.parent_id IN (:artistIds)
          AND track.path IS NOT NULL
          AND track.path NOT LIKE 'artist:%'
          AND track.path NOT LIKE 'album:%'
          ORDER BY album.parent_id, track.path
          """,
      nativeQuery = true)
  List<Object[]> findFirstTrackPathPerArtist(@Param("artistIds") List<UUID> artistIds);

  @Query(
      """
      SELECT i FROM ItemEntity i
      JOIN PlayStateEntity ps ON ps.item = i
      WHERE ps.user.id = :userId
      AND ps.isFavorite = true
      AND i.type = :type
      ORDER BY ps.favoritedAt DESC
      """)
  Page<ItemEntity> findFavoritesByDateAndType(
      @Param("userId") UUID userId, @Param("type") ItemType type, Pageable pageable);

  @Query(
      """
      SELECT i FROM ItemEntity i
      JOIN PlayStateEntity ps ON ps.item = i
      WHERE ps.user.id = :userId
      AND ps.isFavorite = true
      AND i.type = :type
      ORDER BY ps.favoritePosition ASC
      """)
  Page<ItemEntity> findFavoritesByPositionAndType(
      @Param("userId") UUID userId, @Param("type") ItemType type, Pageable pageable);

  @Query(
      """
      SELECT i FROM ItemEntity i
      WHERE i.type = 'AudioTrack'
      AND i.path IS NOT NULL
      AND i.path NOT LIKE 'artist:%'
      AND i.path NOT LIKE 'album:%'
      AND NOT EXISTS (
        SELECT 1 FROM TrackFeaturesEntity tf
        WHERE tf.trackId = i.id
      )
      """)
  List<ItemEntity> findAudioTracksWithoutFeatures(Pageable pageable);

  @Query(
      value = "SELECT id FROM items WHERE type = 'AudioTrack' ORDER BY RANDOM() LIMIT :limit",
      nativeQuery = true)
  List<UUID> findRandomAudioTrackIds(@Param("limit") int limit);

  @Query(
      value =
          """
          SELECT DISTINCT i.id FROM items i
          JOIN item_genres ig ON ig.item_id = i.id
          JOIN genres g ON g.id = ig.genre_id
          WHERE i.type = 'AudioTrack'
            AND LOWER(g.name) IN (:genreNames)
          ORDER BY RANDOM()
          LIMIT :limit
          """,
      nativeQuery = true)
  List<UUID> findRandomAudioTrackIdsByGenre(
      @Param("genreNames") List<String> genreNames, @Param("limit") int limit);

  @Query(
      value =
          """
          SELECT DISTINCT i.id FROM items i
          JOIN item_genres ig ON ig.item_id = i.id
          JOIN genres g ON g.id = ig.genre_id
          WHERE i.type = 'AudioTrack'
            AND LOWER(g.name) LIKE :pattern
          ORDER BY RANDOM()
          LIMIT :limit
          """,
      nativeQuery = true)
  List<UUID> findRandomAudioTrackIdsByGenreLike(
      @Param("pattern") String pattern, @Param("limit") int limit);
}
