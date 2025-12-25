package com.yaytsa.server.domain.service;

import com.yaytsa.server.infra.persistence.entity.AlbumEntity;
import com.yaytsa.server.infra.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infra.persistence.entity.ItemEntity;
import com.yaytsa.server.infra.persistence.entity.ItemType;
import com.yaytsa.server.infra.persistence.query.ItemSpecifications;
import com.yaytsa.server.infra.persistence.repository.AlbumRepository;
import com.yaytsa.server.infra.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infra.persistence.repository.ItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ItemService {

    private final ItemRepository itemRepository;
    private final AudioTrackRepository audioTrackRepository;
    private final AlbumRepository albumRepository;

    public ItemService(
        ItemRepository itemRepository,
        AudioTrackRepository audioTrackRepository,
        AlbumRepository albumRepository
    ) {
        this.itemRepository = itemRepository;
        this.audioTrackRepository = audioTrackRepository;
        this.albumRepository = albumRepository;
    }

    public Optional<ItemEntity> findById(UUID itemId) {
        return itemRepository.findById(itemId);
    }

    public Page<ItemEntity> queryItems(ItemsQueryParams params) {
        if ("DatePlayed".equals(params.sortBy()) && params.userId() != null) {
            return queryRecentlyPlayed(params);
        }

        Specification<ItemEntity> spec = Specification.where(null);

        if (params.includeItemTypes() != null && !params.includeItemTypes().isEmpty()) {
            spec = spec.and(ItemSpecifications.hasTypes(params.includeItemTypes()));
        }

        if (params.parentId() != null) {
            if (params.recursive()) {
                spec = spec.and(ItemSpecifications.isDescendantOf(params.parentId()));
            } else {
                spec = spec.and(ItemSpecifications.hasParent(params.parentId()));
            }
        }

        if (params.artistIds() != null && !params.artistIds().isEmpty()) {
            spec = spec.and(ItemSpecifications.hasArtists(params.artistIds()));
        }

        if (params.albumIds() != null && !params.albumIds().isEmpty()) {
            spec = spec.and(ItemSpecifications.hasAlbums(params.albumIds()));
        }

        if (params.genreIds() != null && !params.genreIds().isEmpty()) {
            spec = spec.and(ItemSpecifications.hasGenres(params.genreIds()));
        }

        if (params.searchTerm() != null && !params.searchTerm().isBlank()) {
            spec = spec.and(ItemSpecifications.searchByName(params.searchTerm()));
        }

        if (params.isFavorite() != null && params.userId() != null) {
            spec = spec.and(ItemSpecifications.isFavoriteForUser(params.userId(), params.isFavorite()));
        }

        Sort sort = buildSort(params.sortBy(), params.sortOrder());
        Pageable pageable = PageRequest.of(
            params.startIndex() / params.limit(),
            params.limit(),
            sort
        );

        return itemRepository.findAll(spec, pageable);
    }

    private Page<ItemEntity> queryRecentlyPlayed(ItemsQueryParams params) {
        Pageable pageable = PageRequest.of(
            params.startIndex() / params.limit(),
            params.limit()
        );

        return itemRepository.findRecentlyPlayedByUser(params.userId(), pageable);
    }

    public List<AudioTrackEntity> getAlbumTracks(UUID albumId) {
        return audioTrackRepository.findByAlbumIdOrderByDiscNoAscTrackNoAsc(albumId);
    }

    public List<AlbumEntity> getArtistAlbums(UUID artistId) {
        return albumRepository.findByAlbumArtistIdOrderBySortNameAsc(artistId);
    }

    private Sort buildSort(String sortBy, String sortOrder) {
        Sort.Direction direction = "Descending".equalsIgnoreCase(sortOrder)
            ? Sort.Direction.DESC
            : Sort.Direction.ASC;

        return switch (sortBy) {
            case "DateCreated" -> Sort.by(direction, "createdAt");
            case "DatePlayed" -> Sort.by(direction, "lastPlayedAt");
            case "Random" -> JpaSort.unsafe("random()");
            case "SortName" -> Sort.by(direction, "sortName");
            default -> Sort.by(direction, "sortName");
        };
    }

    public record ItemsQueryParams(
        UUID userId,
        UUID parentId,
        List<String> includeItemTypes,
        boolean recursive,
        String sortBy,
        String sortOrder,
        int startIndex,
        int limit,
        String searchTerm,
        List<UUID> artistIds,
        List<UUID> albumIds,
        List<UUID> genreIds,
        Boolean isFavorite
    ) {
        public ItemsQueryParams {
            if (sortBy == null) sortBy = "SortName";
            if (sortOrder == null) sortOrder = "Ascending";
            if (limit <= 0) limit = 100;
            if (startIndex < 0) startIndex = 0;
        }
    }
}
