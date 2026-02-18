package com.yaytsa.server.mapper;

import com.yaytsa.server.domain.service.LyricsService;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.AlbumRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ItemMapper {

  private static final long TICKS_PER_MILLISECOND = 10_000L;
  private final LyricsService lyricsService;
  private final AlbumRepository albumRepository;

  public ItemMapper(LyricsService lyricsService, AlbumRepository albumRepository) {
    this.lyricsService = lyricsService;
    this.albumRepository = albumRepository;
  }

  public BaseItemResponse toDto(ItemEntity item, PlayStateEntity playState) {
    return toDto(item, playState, null, null, null);
  }

  public BaseItemResponse toDto(
      ItemEntity item, PlayStateEntity playState, AudioTrackEntity audioTrack, AlbumEntity album) {
    return toDto(item, playState, audioTrack, album, null);
  }

  public BaseItemResponse toDto(
      ItemEntity item,
      PlayStateEntity playState,
      AudioTrackEntity audioTrack,
      AlbumEntity album,
      Integer childCount) {
    String type = mapItemType(item.getType());
    BaseItemResponse.UserItemDataDto userData = playState != null ? mapUserData(playState) : null;

    var builder =
        BaseItemResponse.builder()
            .name(item.getName())
            .id(item.getId().toString())
            .sortName(item.getSortName() != null ? item.getSortName() : item.getName())
            .type(type)
            .isFolder(
                item.getType() == ItemType.MusicAlbum
                    || item.getType() == ItemType.MusicArtist
                    || item.getType() == ItemType.Folder)
            .mediaType("Audio".equals(type) ? "Audio" : null)
            .parentId(item.getParent() != null ? item.getParent().getId().toString() : null)
            .userData(userData)
            .dateCreated(item.getCreatedAt())
            .overview(item.getOverview())
            .path(item.getPath())
            .container(item.getContainer())
            .genres(extractGenres(item))
            .genreItems(extractGenreItems(item))
            .imageTags(extractImageTags(item));

    if (album != null && album.getArtist() != null) {
      ItemEntity artistEntity = album.getArtist();
      var artistPair =
          new BaseItemResponse.NameGuidPair(
              artistEntity.getName(), artistEntity.getId().toString());
      builder
          .artists(List.of(artistEntity.getName()))
          .artistItems(List.of(artistPair))
          .albumArtist(artistEntity.getName())
          .albumArtists(List.of(artistPair));
    }

    if (audioTrack != null) {
      if (audioTrack.getDurationMs() != null) {
        builder.runTimeTicks(audioTrack.getDurationMs() * TICKS_PER_MILLISECOND);
      }
      builder
          .indexNumber(audioTrack.getTrackNumber())
          .parentIndexNumber(audioTrack.getDiscNumber())
          .lyrics(lyricsService.getLyrics(audioTrack));

      if (audioTrack.getAlbum() != null) {
        builder.albumId(audioTrack.getAlbum().getId().toString());
      }

      if (audioTrack.getAlbumArtist() != null) {
        ItemEntity artistItem = audioTrack.getAlbumArtist();
        var artistPair =
            new BaseItemResponse.NameGuidPair(artistItem.getName(), artistItem.getId().toString());
        builder
            .albumArtist(artistItem.getName())
            .albumArtists(List.of(artistPair))
            .artists(List.of(artistItem.getName()))
            .artistItems(List.of(artistPair));
      }
    }

    if (album != null) {
      builder.album(album.getItem().getName());
      if (album.getReleaseDate() != null) {
        builder.premiereDate(
            album.getReleaseDate().atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
        builder.productionYear(album.getReleaseDate().getYear());
      }
    } else if (audioTrack != null && audioTrack.getYear() != null) {
      builder.productionYear(audioTrack.getYear());
    }

    Map<String, String> imageTags = extractImageTags(item);
    if (imageTags != null && imageTags.containsKey("Primary")) {
      builder.albumPrimaryImageTag(imageTags.get("Primary"));
    }

    if (childCount != null) {
      builder.childCount(childCount);
    }

    if (item.getType() == ItemType.MusicAlbum) {
      AlbumEntity albumToUse = album;
      if (albumToUse == null) {
        albumToUse = albumRepository.findById(item.getId()).orElse(null);
      }
      if (albumToUse != null) {
        builder.isComplete(albumToUse.getIsComplete());
        builder.totalTracks(albumToUse.getTotalTracks());
      }
    }

    return builder.build();
  }

  private BaseItemResponse.UserItemDataDto mapUserData(PlayStateEntity playState) {
    Long playbackPositionTicks =
        playState.getPlaybackPositionMs() != null
            ? playState.getPlaybackPositionMs() * TICKS_PER_MILLISECOND
            : 0L;

    return new BaseItemResponse.UserItemDataDto(
        null,
        null,
        null,
        playbackPositionTicks,
        playState.getPlayCount(),
        playState.getIsFavorite(),
        null,
        playState.getLastPlayedAt(),
        playState.getPlayCount() != null && playState.getPlayCount() > 0,
        null,
        playState.getItemId().toString());
  }

  private List<String> extractGenres(ItemEntity item) {
    if (item.getItemGenres() == null || item.getItemGenres().isEmpty()) {
      return List.of();
    }
    return item.getItemGenres().stream()
        .map(ig -> ig.getGenre().getName())
        .collect(Collectors.toList());
  }

  private List<BaseItemResponse.NameGuidPair> extractGenreItems(ItemEntity item) {
    if (item.getItemGenres() == null || item.getItemGenres().isEmpty()) {
      return List.of();
    }
    return item.getItemGenres().stream()
        .map(
            ig ->
                new BaseItemResponse.NameGuidPair(
                    ig.getGenre().getName(), ig.getGenre().getId().toString()))
        .collect(Collectors.toList());
  }

  private Map<String, String> extractImageTags(ItemEntity item) {
    if (item.getImages() == null || item.getImages().isEmpty()) {
      return Map.of();
    }

    Optional<ImageEntity> primaryImage =
        item.getImages().stream().filter(img -> img.getType() == ImageType.Primary).findFirst();

    if (primaryImage.isEmpty()) {
      return Map.of();
    }

    String tag =
        primaryImage.get().getTag() != null
            ? primaryImage.get().getTag()
            : primaryImage.get().getId().toString();

    return Map.of("Primary", tag);
  }

  private String mapItemType(ItemType itemType) {
    return switch (itemType) {
      case AudioTrack -> "Audio";
      case MusicAlbum -> "MusicAlbum";
      case MusicArtist -> "MusicArtist";
      case Folder -> "Folder";
      case Playlist -> "Playlist";
    };
  }
}
