package com.yaytsa.server.domain.service;

import com.yaytsa.server.error.ItemNotFoundException;
import com.yaytsa.server.error.PlaylistEntryNotFoundException;
import com.yaytsa.server.error.PlaylistNotFoundException;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemType;
import com.yaytsa.server.infrastructure.persistence.entity.PlaylistEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlaylistEntryEntity;
import com.yaytsa.server.infrastructure.persistence.query.OffsetBasedPageRequest;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaylistEntryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlaylistRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlaylistService {

  private final PlaylistRepository playlistRepository;
  private final PlaylistEntryRepository playlistEntryRepository;
  private final ItemRepository itemRepository;

  public PlaylistService(
      PlaylistRepository playlistRepository,
      PlaylistEntryRepository playlistEntryRepository,
      ItemRepository itemRepository) {
    this.playlistRepository = playlistRepository;
    this.playlistEntryRepository = playlistEntryRepository;
    this.itemRepository = itemRepository;
  }

  public PlaylistEntity createPlaylist(UUID userId, String name, List<UUID> itemIds) {
    ItemEntity itemEntity = new ItemEntity();
    itemEntity.setType(ItemType.Playlist);
    itemEntity.setName(name);
    itemEntity.setSortName(name.toLowerCase(java.util.Locale.ROOT));
    itemEntity.setPath("playlist:" + UUID.randomUUID());
    itemEntity = itemRepository.save(itemEntity);

    PlaylistEntity playlist = new PlaylistEntity();
    playlist.setId(itemEntity.getId());
    playlist.setUserId(userId);
    playlist.setName(name);

    playlist = playlistRepository.save(playlist);

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
    Pageable pageable =
        new OffsetBasedPageRequest(startIndex, limit, Sort.by(Sort.Direction.ASC, "position"));
    return playlistEntryRepository.findByPlaylistId(playlistId, pageable);
  }

  public void addItemsToPlaylist(UUID playlistId, List<UUID> itemIds) {
    if (itemIds == null || itemIds.isEmpty()) {
      return;
    }
    if (!playlistRepository.existsById(playlistId)) {
      throw new PlaylistNotFoundException("Playlist not found: " + playlistId);
    }

    Set<UUID> existingItemIds =
        itemRepository.findAllById(itemIds).stream()
            .map(ItemEntity::getId)
            .collect(Collectors.toSet());

    List<UUID> missingIds = itemIds.stream().filter(id -> !existingItemIds.contains(id)).toList();

    if (!missingIds.isEmpty()) {
      throw new ItemNotFoundException(missingIds);
    }

    int maxPosition = playlistEntryRepository.findMaxPositionByPlaylistId(playlistId).orElse(-1);

    List<PlaylistEntryEntity> entries = new ArrayList<>(itemIds.size());
    for (int i = 0; i < itemIds.size(); i++) {
      PlaylistEntryEntity entry = new PlaylistEntryEntity();
      entry.setId(UUID.randomUUID());
      entry.setPlaylistId(playlistId);
      entry.setItemId(itemIds.get(i));
      entry.setPosition(maxPosition + i + 1);
      entries.add(entry);
    }
    playlistEntryRepository.saveAll(entries);
  }

  public void removeItemsFromPlaylist(UUID playlistId, List<UUID> entryIds) {
    playlistEntryRepository.deleteAllById(entryIds);
    reorderPlaylistEntries(playlistId);
  }

  public void movePlaylistItem(UUID playlistId, UUID entryId, int newIndex) {
    PlaylistEntryEntity entry =
        playlistEntryRepository
            .findById(entryId)
            .orElseThrow(
                () -> new PlaylistEntryNotFoundException("Playlist entry not found: " + entryId));

    if (!entry.getPlaylistId().equals(playlistId)) {
      throw new IllegalArgumentException("Entry does not belong to this playlist");
    }

    int oldPosition = entry.getPosition();
    if (oldPosition == newIndex) {
      return;
    }

    List<PlaylistEntryEntity> allEntries =
        playlistEntryRepository.findByPlaylistIdOrderByPositionAsc(playlistId);

    PlaylistEntryEntity movedEntry =
        allEntries.stream().filter(e -> e.getId().equals(entryId)).findFirst().orElseThrow();

    allEntries.remove(movedEntry);

    int insertIndex = Math.min(newIndex, allEntries.size());
    allEntries.add(insertIndex, movedEntry);

    int offset = allEntries.size() + 1000;
    for (int i = 0; i < allEntries.size(); i++) {
      allEntries.get(i).setPosition(offset + i);
    }
    playlistEntryRepository.saveAll(allEntries);
    playlistEntryRepository.flush();

    for (int i = 0; i < allEntries.size(); i++) {
      allEntries.get(i).setPosition(i);
    }
    playlistEntryRepository.saveAll(allEntries);
  }

  public Optional<PlaylistEntity> updatePlaylist(UUID playlistId, String name) {
    return playlistRepository
        .findById(playlistId)
        .map(
            playlist -> {
              if (name != null && !name.isBlank()) {
                playlist.setName(name);
                itemRepository
                    .findById(playlistId)
                    .ifPresent(
                        item -> {
                          item.setName(name);
                          item.setSortName(name.toLowerCase(java.util.Locale.ROOT));
                          itemRepository.save(item);
                        });
              }
              return playlistRepository.save(playlist);
            });
  }

  public void deletePlaylist(UUID playlistId) {
    playlistEntryRepository.deleteByPlaylistId(playlistId);
    playlistRepository.deleteById(playlistId);
    itemRepository.deleteById(playlistId);
  }

  @Transactional(readOnly = true)
  public List<PlaylistEntity> getUserPlaylists(UUID userId) {
    return playlistRepository.findByUserIdOrderByCreatedAtDesc(userId);
  }

  @Transactional(readOnly = true)
  public long getPlaylistItemCount(UUID playlistId) {
    return playlistEntryRepository.countByPlaylistId(playlistId);
  }

  private void reorderPlaylistEntries(UUID playlistId) {
    List<PlaylistEntryEntity> entries =
        playlistEntryRepository.findByPlaylistIdOrderByPositionAsc(playlistId);

    if (entries.isEmpty()) {
      return;
    }

    int offset = entries.size() + 1000;
    for (int i = 0; i < entries.size(); i++) {
      entries.get(i).setPosition(offset + i);
    }
    playlistEntryRepository.saveAll(entries);
    playlistEntryRepository.flush();

    for (int i = 0; i < entries.size(); i++) {
      entries.get(i).setPosition(i);
    }
    playlistEntryRepository.saveAll(entries);
  }
}
