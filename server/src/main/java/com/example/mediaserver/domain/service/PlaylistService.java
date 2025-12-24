package com.example.mediaserver.domain.service;

import com.example.mediaserver.infra.persistence.entity.PlaylistEntity;
import com.example.mediaserver.infra.persistence.entity.PlaylistEntryEntity;
import com.example.mediaserver.infra.persistence.repository.PlaylistEntryRepository;
import com.example.mediaserver.infra.persistence.repository.PlaylistRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PlaylistService {

    private final PlaylistRepository playlistRepository;
    private final PlaylistEntryRepository playlistEntryRepository;

    public PlaylistService(
        PlaylistRepository playlistRepository,
        PlaylistEntryRepository playlistEntryRepository
    ) {
        this.playlistRepository = playlistRepository;
        this.playlistEntryRepository = playlistEntryRepository;
    }

    public PlaylistEntity createPlaylist(UUID userId, String name, List<UUID> itemIds) {
        PlaylistEntity playlist = new PlaylistEntity();
        playlist.setId(UUID.randomUUID());
        playlist.setUserId(userId);
        playlist.setName(name);
        playlist.setCreatedAt(OffsetDateTime.now());

        playlistRepository.save(playlist);

        if (itemIds != null && !itemIds.isEmpty()) {
            addItemsToPlaylist(playlist.getId(), itemIds);
        }

        return playlist;
    }

    @Transactional(readOnly = true)
    public Optional<PlaylistEntity> getPlaylist(UUID playlistId) {
        return playlistRepository.findById(playlistId);
    }

    @Transactional(readOnly = true)
    public Page<PlaylistEntryEntity> getPlaylistItems(UUID playlistId, int startIndex, int limit) {
        Pageable pageable = PageRequest.of(
            startIndex / limit,
            limit,
            Sort.by(Sort.Direction.ASC, "position")
        );
        return playlistEntryRepository.findByPlaylistId(playlistId, pageable);
    }

    public void addItemsToPlaylist(UUID playlistId, List<UUID> itemIds) {
        int maxPosition = playlistEntryRepository.findMaxPositionByPlaylistId(playlistId)
            .orElse(-1);

        for (int i = 0; i < itemIds.size(); i++) {
            PlaylistEntryEntity entry = new PlaylistEntryEntity();
            entry.setId(UUID.randomUUID());
            entry.setPlaylistId(playlistId);
            entry.setItemId(itemIds.get(i));
            entry.setPosition(maxPosition + i + 1);

            playlistEntryRepository.save(entry);
        }
    }

    public void removeItemsFromPlaylist(UUID playlistId, List<UUID> entryIds) {
        playlistEntryRepository.deleteAllById(entryIds);
        reorderPlaylistEntries(playlistId);
    }

    public void movePlaylistItem(UUID playlistId, UUID entryId, int newIndex) {
        PlaylistEntryEntity entry = playlistEntryRepository.findById(entryId)
            .orElseThrow(() -> new PlaylistEntryNotFoundException("Playlist entry not found: " + entryId));

        if (!entry.getPlaylistId().equals(playlistId)) {
            throw new IllegalArgumentException("Entry does not belong to this playlist");
        }

        int oldPosition = entry.getPosition();
        List<PlaylistEntryEntity> allEntries = playlistEntryRepository
            .findByPlaylistIdOrderByPositionAsc(playlistId);

        if (oldPosition < newIndex) {
            for (PlaylistEntryEntity e : allEntries) {
                if (e.getPosition() > oldPosition && e.getPosition() <= newIndex) {
                    e.setPosition(e.getPosition() - 1);
                    playlistEntryRepository.save(e);
                }
            }
        } else if (oldPosition > newIndex) {
            for (PlaylistEntryEntity e : allEntries) {
                if (e.getPosition() >= newIndex && e.getPosition() < oldPosition) {
                    e.setPosition(e.getPosition() + 1);
                    playlistEntryRepository.save(e);
                }
            }
        }

        entry.setPosition(newIndex);
        playlistEntryRepository.save(entry);
    }

    public void deletePlaylist(UUID playlistId) {
        playlistEntryRepository.deleteByPlaylistId(playlistId);
        playlistRepository.deleteById(playlistId);
    }

    @Transactional(readOnly = true)
    public List<PlaylistEntity> getUserPlaylists(UUID userId) {
        return playlistRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private void reorderPlaylistEntries(UUID playlistId) {
        List<PlaylistEntryEntity> entries = playlistEntryRepository
            .findByPlaylistIdOrderByPositionAsc(playlistId);

        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).setPosition(i);
            playlistEntryRepository.save(entries.get(i));
        }
    }

    public static class PlaylistEntryNotFoundException extends RuntimeException {
        public PlaylistEntryNotFoundException(String message) {
            super(message);
        }
    }
}
