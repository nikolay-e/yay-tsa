package com.example.mediaserver.infra.persistence.query;

import com.example.mediaserver.infra.persistence.entity.*;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

public final class ItemSpecifications {

    private ItemSpecifications() {}

    public static Specification<ItemEntity> hasTypes(List<String> types) {
        return (root, query, cb) -> {
            if (types == null || types.isEmpty()) {
                return cb.conjunction();
            }
            List<ItemType> itemTypes = types.stream()
                .map(ItemType::valueOf)
                .toList();
            return root.get("type").in(itemTypes);
        };
    }

    public static Specification<ItemEntity> hasParent(UUID parentId) {
        return (root, query, cb) -> {
            if (parentId == null) {
                return cb.isNull(root.get("parent"));
            }
            return cb.equal(root.get("parent").get("id"), parentId);
        };
    }

    public static Specification<ItemEntity> isDescendantOf(UUID parentId) {
        return (root, query, cb) -> {
            if (parentId == null) {
                return cb.conjunction();
            }
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<ItemEntity> subRoot = subquery.from(ItemEntity.class);

            subquery.select(subRoot.get("id"))
                .where(
                    cb.or(
                        cb.equal(subRoot.get("parent").get("id"), parentId),
                        cb.equal(subRoot.get("id"), parentId)
                    )
                );

            return cb.or(
                cb.equal(root.get("parent").get("id"), parentId),
                root.get("parent").get("id").in(subquery)
            );
        };
    }

    public static Specification<ItemEntity> hasArtists(List<UUID> artistIds) {
        return (root, query, cb) -> {
            if (artistIds == null || artistIds.isEmpty()) {
                return cb.conjunction();
            }

            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<AudioTrackEntity> trackRoot = subquery.from(AudioTrackEntity.class);
            subquery.select(trackRoot.get("item").get("id"))
                .where(trackRoot.get("albumArtist").get("id").in(artistIds));

            Subquery<UUID> albumSubquery = query.subquery(UUID.class);
            Root<AlbumEntity> albumRoot = albumSubquery.from(AlbumEntity.class);
            albumSubquery.select(albumRoot.get("item").get("id"))
                .where(albumRoot.get("artist").get("id").in(artistIds));

            return cb.or(
                root.get("id").in(artistIds),
                root.get("id").in(subquery),
                root.get("id").in(albumSubquery)
            );
        };
    }

    public static Specification<ItemEntity> hasAlbums(List<UUID> albumIds) {
        return (root, query, cb) -> {
            if (albumIds == null || albumIds.isEmpty()) {
                return cb.conjunction();
            }

            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<AudioTrackEntity> trackRoot = subquery.from(AudioTrackEntity.class);
            subquery.select(trackRoot.get("item").get("id"))
                .where(trackRoot.get("album").get("id").in(albumIds));

            return cb.or(
                root.get("id").in(albumIds),
                root.get("id").in(subquery)
            );
        };
    }

    public static Specification<ItemEntity> hasGenres(List<UUID> genreIds) {
        return (root, query, cb) -> {
            if (genreIds == null || genreIds.isEmpty()) {
                return cb.conjunction();
            }

            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<ItemGenreEntity> genreRoot = subquery.from(ItemGenreEntity.class);
            subquery.select(genreRoot.get("item").get("id"))
                .where(genreRoot.get("genre").get("id").in(genreIds));

            return root.get("id").in(subquery);
        };
    }

    public static Specification<ItemEntity> searchByName(String searchTerm) {
        return (root, query, cb) -> {
            if (searchTerm == null || searchTerm.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + searchTerm.toLowerCase() + "%";
            return cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("sortName")), pattern)
            );
        };
    }

    public static Specification<ItemEntity> isFavoriteForUser(UUID userId, Boolean isFavorite) {
        return (root, query, cb) -> {
            if (userId == null || isFavorite == null) {
                return cb.conjunction();
            }

            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<PlayStateEntity> playStateRoot = subquery.from(PlayStateEntity.class);
            subquery.select(playStateRoot.get("item").get("id"))
                .where(
                    cb.and(
                        cb.equal(playStateRoot.get("user").get("id"), userId),
                        cb.equal(playStateRoot.get("isFavorite"), true)
                    )
                );

            if (isFavorite) {
                return root.get("id").in(subquery);
            } else {
                return cb.not(root.get("id").in(subquery));
            }
        };
    }

    public static Specification<ItemEntity> byLibraryRoot(String libraryRoot) {
        return (root, query, cb) -> {
            if (libraryRoot == null || libraryRoot.isBlank()) {
                return cb.conjunction();
            }
            return cb.equal(root.get("libraryRoot"), libraryRoot);
        };
    }
}
