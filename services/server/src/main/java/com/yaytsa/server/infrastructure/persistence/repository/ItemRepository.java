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
      """
      SELECT i FROM ItemEntity i
      JOIN PlayStateEntity ps ON ps.item = i
      WHERE ps.user.id = :userId
      AND ps.lastPlayedAt IS NOT NULL
      ORDER BY ps.lastPlayedAt DESC
      """)
  Page<ItemEntity> findRecentlyPlayedByUser(@Param("userId") UUID userId, Pageable pageable);

  @Query(
      """
      SELECT a FROM ItemEntity a
      WHERE a.type = 'MusicAlbum'
      AND a.id IN (
        SELECT at.album.id FROM AudioTrackEntity at
        JOIN PlayStateEntity ps ON ps.item.id = at.item.id
        WHERE ps.user.id = :userId
        AND ps.lastPlayedAt IS NOT NULL
      )
      ORDER BY (
        SELECT MAX(ps2.lastPlayedAt) FROM AudioTrackEntity at2
        JOIN PlayStateEntity ps2 ON ps2.item.id = at2.item.id
        WHERE at2.album.id = a.id AND ps2.user.id = :userId
      ) DESC
      """)
  Page<ItemEntity> findRecentlyPlayedAlbumsByUser(@Param("userId") UUID userId, Pageable pageable);

  @Query(
      value =
          """
          SELECT * FROM items
          WHERE search_vector @@ plainto_tsquery('english', :searchTerm)
          ORDER BY ts_rank(search_vector, plainto_tsquery('english', :searchTerm)) DESC
          """,
      nativeQuery = true)
  List<ItemEntity> searchFullText(@Param("searchTerm") String searchTerm);

  @Query(
      value =
          """
          SELECT * FROM items
          WHERE search_vector @@ plainto_tsquery('english', :searchTerm)
          AND type = :itemType
          ORDER BY ts_rank(search_vector, plainto_tsquery('english', :searchTerm)) DESC
          """,
      nativeQuery = true)
  List<ItemEntity> searchFullTextByType(
      @Param("searchTerm") String searchTerm, @Param("itemType") String itemType);

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
}
