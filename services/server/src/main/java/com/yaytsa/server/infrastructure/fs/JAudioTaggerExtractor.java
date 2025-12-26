package com.yaytsa.server.infrastructure.fs;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JAudioTaggerExtractor {

  private static final Logger log = LoggerFactory.getLogger(JAudioTaggerExtractor.class);

  static {
    java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
  }

  public Optional<AudioMetadata> extract(Path filePath) {
    try {
      AudioFile audioFile = AudioFileIO.read(filePath.toFile());
      AudioHeader header = audioFile.getAudioHeader();
      Tag tag = audioFile.getTag();

      String title = getTagValue(tag, FieldKey.TITLE);
      if (title == null || title.isBlank()) {
        title = filePath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
      }

      String artist = getTagValue(tag, FieldKey.ARTIST);
      String albumArtist = getTagValue(tag, FieldKey.ALBUM_ARTIST);
      if (albumArtist == null || albumArtist.isBlank()) {
        albumArtist = artist;
      }

      String album = getTagValue(tag, FieldKey.ALBUM);
      Integer trackNumber = parseInteger(getTagValue(tag, FieldKey.TRACK));
      Integer discNumber = parseInteger(getTagValue(tag, FieldKey.DISC_NO));
      Integer year = parseYear(getTagValue(tag, FieldKey.YEAR));
      String comment = getTagValue(tag, FieldKey.COMMENT);
      String lyrics = getTagValue(tag, FieldKey.LYRICS);

      List<String> genres = parseGenres(getTagValue(tag, FieldKey.GENRE));

      long durationMs = header.getTrackLength() * 1000L;
      int bitrate = (int) header.getBitRateAsNumber();
      int sampleRate = header.getSampleRateAsNumber();
      int channels = parseChannels(header.getChannels());
      String codec = header.getEncodingType();

      byte[] artworkData = null;
      String artworkMimeType = null;
      if (tag != null) {
        Artwork artwork = tag.getFirstArtwork();
        if (artwork != null) {
          artworkData = artwork.getBinaryData();
          artworkMimeType = artwork.getMimeType();
        }
      }

      return Optional.of(
          new AudioMetadata(
              title,
              artist,
              albumArtist,
              album,
              trackNumber,
              discNumber,
              year,
              durationMs,
              bitrate,
              sampleRate,
              channels,
              codec,
              comment,
              lyrics,
              genres,
              artworkData,
              artworkMimeType));

    } catch (Exception e) {
      log.warn("Failed to extract metadata from {}: {}", filePath, e.getMessage());
      return Optional.empty();
    }
  }

  private String getTagValue(Tag tag, FieldKey key) {
    if (tag == null) return null;
    try {
      String value = tag.getFirst(key);
      return (value != null && !value.isBlank()) ? value.trim() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private Integer parseInteger(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      String cleaned = value.split("/")[0].trim();
      return Integer.parseInt(cleaned);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Integer parseYear(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      String cleaned = value.substring(0, Math.min(4, value.length())).trim();
      int year = Integer.parseInt(cleaned);
      return (year >= 1000 && year <= 9999) ? year : null;
    } catch (Exception e) {
      return null;
    }
  }

  private int parseChannels(String channels) {
    if (channels == null) return 2;
    String lower = channels.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("mono")) return 1;
    if (lower.contains("stereo")) return 2;
    try {
      return Integer.parseInt(channels.replaceAll("[^0-9]", ""));
    } catch (Exception e) {
      return 2;
    }
  }

  private List<String> parseGenres(String genreString) {
    if (genreString == null || genreString.isBlank()) return List.of();
    return Arrays.stream(genreString.split("[;,/]"))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .map(this::normalizeGenre)
        .distinct()
        .toList();
  }

  private String normalizeGenre(String genre) {
    if (genre.matches("\\(\\d+\\)")) {
      return genre;
    }
    return genre.replaceAll("\\(\\d+\\)", "").trim();
  }
}
