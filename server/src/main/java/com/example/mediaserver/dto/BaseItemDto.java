package com.example.mediaserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BaseItemDto(
    @JsonProperty("Id") UUID id,
    @JsonProperty("Name") String name,
    @JsonProperty("SortName") String sortName,
    @JsonProperty("Type") String type,
    @JsonProperty("ParentId") UUID parentId,
    @JsonProperty("Path") String path,
    @JsonProperty("Container") String container,
    @JsonProperty("Overview") String overview,
    @JsonProperty("RunTimeTicks") Long runTimeTicks,
    @JsonProperty("DateCreated") OffsetDateTime dateCreated,
    @JsonProperty("PremiereDate") OffsetDateTime premiereDate,
    @JsonProperty("ProductionYear") Integer productionYear,
    @JsonProperty("IndexNumber") Integer indexNumber,
    @JsonProperty("ParentIndexNumber") Integer parentIndexNumber,
    @JsonProperty("AlbumId") UUID albumId,
    @JsonProperty("AlbumPrimaryImageTag") String albumPrimaryImageTag,
    @JsonProperty("AlbumArtist") String albumArtist,
    @JsonProperty("AlbumArtists") List<NameIdPair> albumArtists,
    @JsonProperty("Artists") List<String> artists,
    @JsonProperty("ArtistItems") List<NameIdPair> artistItems,
    @JsonProperty("Genres") List<String> genres,
    @JsonProperty("GenreItems") List<NameIdPair> genreItems,
    @JsonProperty("UserData") UserDataDto userData,
    @JsonProperty("MediaType") String mediaType,
    @JsonProperty("ImageTags") ImageTags imageTags,
    @JsonProperty("BackdropImageTags") List<String> backdropImageTags,
    @JsonProperty("ChildCount") Integer childCount,
    @JsonProperty("TotalRecordCount") Integer totalRecordCount
) {
    public record NameIdPair(
        @JsonProperty("Name") String name,
        @JsonProperty("Id") UUID id
    ) {}

    public record ImageTags(
        @JsonProperty("Primary") String primary
    ) {}
}
