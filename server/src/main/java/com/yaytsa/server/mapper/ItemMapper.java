package com.yaytsa.server.mapper;

import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.infra.persistence.entity.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ItemMapper {

    private static final long TICKS_PER_MILLISECOND = 10_000L;

    public BaseItemResponse toDto(ItemEntity item, PlayStateEntity playState) {
        return toDto(item, playState, null, null);
    }

    public BaseItemResponse toDto(
        ItemEntity item,
        PlayStateEntity playState,
        AudioTrackEntity audioTrack,
        AlbumEntity album
    ) {
        String type = mapItemType(item.getType());
        BaseItemResponse.UserItemDataDto userData = playState != null ? mapUserData(playState) : null;

        var builder = BaseItemResponse.builder()
            .name(item.getName())
            .id(item.getId().toString())
            .sortName(item.getSortName() != null ? item.getSortName() : item.getName())
            .type(type)
            .isFolder(item.getType() == ItemType.MusicAlbum ||
                      item.getType() == ItemType.MusicArtist ||
                      item.getType() == ItemType.Folder)
            .mediaType(type.equals("Audio") ? "Audio" : null)
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
            var artistPair = new BaseItemResponse.NameGuidPair(
                artistEntity.getName(), artistEntity.getId().toString()
            );
            builder.artists(List.of(artistEntity.getName()))
                   .artistItems(List.of(artistPair))
                   .albumArtist(artistEntity.getName())
                   .albumArtists(List.of(artistPair));
        }

        if (audioTrack != null) {
            if (audioTrack.getDurationMs() != null) {
                builder.runTimeTicks(audioTrack.getDurationMs() * TICKS_PER_MILLISECOND);
            }
            builder.indexNumber(audioTrack.getTrackNumber())
                   .parentIndexNumber(audioTrack.getDiscNumber());

            if (audioTrack.getAlbum() != null) {
                builder.albumId(audioTrack.getAlbum().getId().toString());
            }

            if (audioTrack.getAlbumArtist() != null) {
                ItemEntity artistItem = audioTrack.getAlbumArtist();
                var artistPair = new BaseItemResponse.NameGuidPair(
                    artistItem.getName(), artistItem.getId().toString()
                );
                builder.albumArtist(artistItem.getName())
                       .albumArtists(List.of(artistPair));
            }
        }

        if (album != null) {
            builder.album(album.getItem().getName());
            if (album.getReleaseDate() != null) {
                builder.premiereDate(album.getReleaseDate()
                    .atStartOfDay()
                    .atOffset(java.time.ZoneOffset.UTC));
                builder.productionYear(album.getReleaseDate().getYear());
            }
        } else if (audioTrack != null && audioTrack.getYear() != null) {
            builder.productionYear(audioTrack.getYear());
        }

        Map<String, String> imageTags = extractImageTags(item);
        if (imageTags != null && imageTags.containsKey("Primary")) {
            builder.albumPrimaryImageTag(imageTags.get("Primary"));
        }

        return builder.build();
    }

    private BaseItemResponse.UserItemDataDto mapUserData(PlayStateEntity playState) {
        Long playbackPositionTicks = playState.getPlaybackPositionMs() != null
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
            playState.getItemId().toString()
        );
    }

    private List<String> extractGenres(ItemEntity item) {
        if (item.getItemGenres() == null || item.getItemGenres().isEmpty()) {
            return null;
        }
        return item.getItemGenres().stream()
            .map(ig -> ig.getGenre().getName())
            .collect(Collectors.toList());
    }

    private List<BaseItemResponse.NameGuidPair> extractGenreItems(ItemEntity item) {
        if (item.getItemGenres() == null || item.getItemGenres().isEmpty()) {
            return null;
        }
        return item.getItemGenres().stream()
            .map(ig -> new BaseItemResponse.NameGuidPair(
                ig.getGenre().getName(),
                ig.getGenre().getId().toString()
            ))
            .collect(Collectors.toList());
    }

    private Map<String, String> extractImageTags(ItemEntity item) {
        if (item.getImages() == null || item.getImages().isEmpty()) {
            return null;
        }

        Optional<ImageEntity> primaryImage = item.getImages().stream()
            .filter(img -> img.getType() == ImageType.Primary)
            .findFirst();

        if (primaryImage.isEmpty()) {
            return null;
        }

        String tag = primaryImage.get().getTag() != null
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
