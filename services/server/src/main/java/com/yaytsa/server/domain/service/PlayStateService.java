package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.entity.PlayStateEntity;
import com.yaytsa.server.infrastructure.persistence.entity.UserEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.infrastructure.persistence.repository.PlayStateRepository;
import com.yaytsa.server.infrastructure.persistence.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlayStateService {

  private final PlayStateRepository playStateRepository;
  private final UserRepository userRepository;
  private final ItemRepository itemRepository;

  public PlayStateService(
      PlayStateRepository playStateRepository,
      UserRepository userRepository,
      ItemRepository itemRepository) {
    this.playStateRepository = playStateRepository;
    this.userRepository = userRepository;
    this.itemRepository = itemRepository;
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
    PlayStateEntity playState = findOrCreatePlayState(userId, itemId);
    playState.setIsFavorite(isFavorite);
    playStateRepository.save(playState);
  }

  public void incrementPlayCount(UUID userId, UUID itemId) {
    int updated = playStateRepository.incrementPlayCount(userId, itemId, OffsetDateTime.now());
    if (updated == 0) {
      PlayStateEntity playState = findOrCreatePlayState(userId, itemId);
      playState.setPlayCount(1);
      playState.setLastPlayedAt(OffsetDateTime.now());
      playStateRepository.save(playState);
    }
  }

  public void updatePlaybackPosition(UUID userId, UUID itemId, long positionMs) {
    PlayStateEntity playState = findOrCreatePlayState(userId, itemId);
    playState.setPlaybackPositionMs(positionMs);
    playStateRepository.save(playState);
  }

  private PlayStateEntity findOrCreatePlayState(UUID userId, UUID itemId) {
    return playStateRepository
        .findByUserIdAndItemId(userId, itemId)
        .orElseGet(
            () -> {
              UserEntity user =
                  userRepository
                      .findById(userId)
                      .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
              ItemEntity item =
                  itemRepository
                      .findById(itemId)
                      .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

              PlayStateEntity newPlayState = new PlayStateEntity();
              newPlayState.setId(UUID.randomUUID());
              newPlayState.setUser(user);
              newPlayState.setItem(item);
              newPlayState.setIsFavorite(false);
              newPlayState.setPlayCount(0);
              newPlayState.setPlaybackPositionMs(0L);

              try {
                playStateRepository.saveAndFlush(newPlayState);
              } catch (DataIntegrityViolationException e) {
                return playStateRepository
                    .findByUserIdAndItemId(userId, itemId)
                    .orElseThrow(() -> e);
              }
              return newPlayState;
            });
  }
}
