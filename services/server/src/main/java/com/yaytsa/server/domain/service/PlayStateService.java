package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.PlayStateEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlayStateRepository;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlayStateService {

  private final PlayStateRepository playStateRepository;

  public PlayStateService(PlayStateRepository playStateRepository) {
    this.playStateRepository = playStateRepository;
  }

  @Transactional(readOnly = true)
  public Optional<PlayStateEntity> getPlayState(UUID userId, UUID itemId) {
    return playStateRepository.findByUserIdAndItemId(userId, itemId);
  }

  @Transactional(readOnly = true)
  public Map<UUID, PlayStateEntity> getPlayStatesForItems(UUID userId, Collection<UUID> itemIds) {
    if (itemIds == null || itemIds.isEmpty()) {
      return Map.of();
    }
    return playStateRepository.findAllByUserIdAndItemIdIn(userId, itemIds).stream()
        .collect(Collectors.toMap(ps -> ps.getItem().getId(), Function.identity()));
  }

  public void setFavorite(UUID userId, UUID itemId, boolean isFavorite) {
    playStateRepository.upsertFavorite(userId, itemId, isFavorite);
  }

  public void incrementPlayCount(UUID userId, UUID itemId) {
    playStateRepository.upsertPlayCount(userId, itemId, OffsetDateTime.now());
  }

  public void updatePlaybackPosition(UUID userId, UUID itemId, long positionMs) {
    playStateRepository.upsertPlaybackPosition(userId, itemId, positionMs);
  }
}
