package com.example.mediaserver.mapper;

import com.example.mediaserver.dto.BaseItemDto;
import com.example.mediaserver.dto.UserDataDto;
import com.example.mediaserver.infra.persistence.entity.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ItemMapper {

    private static final long TICKS_PER_MILLISECOND = 10_000L;

    public BaseItemDto toDto(ItemEntity item, PlayStateEntity playState) {
        return toDto(item, playState, null, null);
    }

    public BaseItemDto toDto(
        ItemEntity item,
        PlayStateEntity playState,
        AudioTrackEntity audioTrack,
        AlbumEntity album
    ) {
        String type = mapItemType(item.getType());
        UserDataDto userData = playState != null ? mapUserData(playState) : null;

        Long runTimeTicks = null;
        Integer indexNumber = null;
        Integer parentIndexNumber = null;
        UUID albumId = null;
        String albumArtist = null;
        List<BaseItemDto.NameIdPair> albumArtists = null;
        List<String> artists = null;
        List<BaseItemDto.NameIdPair> artistItems = null;

        if (album != null && album.getArtist() != null) {
            ItemEntity artistEntity = album.getArtist();
            artists = List.of(artistEntity.getName());
            artistItems = List.of(
                new BaseItemDto.NameIdPair(artistEntity.getName(), artistEntity.getId())
            );
            albumArtist = artistEntity.getName();
            albumArtists = List.of(
                new BaseItemDto.NameIdPair(artistEntity.getName(), artistEntity.getId())
            );
        }

        if (audioTrack != null) {
            if (audioTrack.getDurationMs() != null) {
                runTimeTicks = audioTrack.getDurationMs() * TICKS_PER_MILLISECOND;
            }
            indexNumber = audioTrack.getTrackNumber();
            parentIndexNumber = audioTrack.getDiscNumber();

            if (audioTrack.getAlbum() != null) {
                albumId = audioTrack.getAlbum().getId();
            }

            if (audioTrack.getAlbumArtist() != null) {
                ItemEntity artistItem = audioTrack.getAlbumArtist();
                albumArtist = artistItem.getName();
                albumArtists = List.of(
                    new BaseItemDto.NameIdPair(artistItem.getName(), artistItem.getId())
                );
            }
        }

        List<String> genres = extractGenres(item);
        List<BaseItemDto.NameIdPair> genreItems = extractGenreItems(item);
        BaseItemDto.ImageTags imageTags = extractImageTags(item);

        Integer productionYear = null;
        if (album != null && album.getReleaseDate() != null) {
            productionYear = album.getReleaseDate().getYear();
        } else if (audioTrack != null && audioTrack.getYear() != null) {
            productionYear = audioTrack.getYear();
        }

        return new BaseItemDto(
            item.getId(),
            item.getName(),
            item.getSortName() != null ? item.getSortName() : item.getName(),
            type,
            item.getParent() != null ? item.getParent().getId() : null,
            item.getPath(),
            item.getContainer(),
            item.getOverview(),
            runTimeTicks,
            item.getCreatedAt(),
            album != null && album.getReleaseDate() != null
                ? album.getReleaseDate().atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
                : null,
            productionYear,
            indexNumber,
            parentIndexNumber,
            albumId,
            null,
            albumArtist,
            albumArtists,
            artists,
            artistItems,
            genres,
            genreItems,
            userData,
            type.equals("Audio") ? "Audio" : null,
            imageTags,
            null,
            null,
            null
        );
    }

    private UserDataDto mapUserData(PlayStateEntity playState) {
        Long playbackPositionTicks = playState.getPlaybackPositionMs() != null
            ? playState.getPlaybackPositionMs() * TICKS_PER_MILLISECOND
            : 0L;

        return new UserDataDto(
            playbackPositionTicks,
            playState.getPlayCount(),
            playState.getIsFavorite(),
            playState.getLastPlayedAt(),
            playState.getPlayCount() != null && playState.getPlayCount() > 0
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

    private List<BaseItemDto.NameIdPair> extractGenreItems(ItemEntity item) {
        if (item.getItemGenres() == null || item.getItemGenres().isEmpty()) {
            return null;
        }
        return item.getItemGenres().stream()
            .map(ig -> new BaseItemDto.NameIdPair(
                ig.getGenre().getName(),
                ig.getGenre().getId()
            ))
            .collect(Collectors.toList());
    }

    private BaseItemDto.ImageTags extractImageTags(ItemEntity item) {
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

        return new BaseItemDto.ImageTags(tag);
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
