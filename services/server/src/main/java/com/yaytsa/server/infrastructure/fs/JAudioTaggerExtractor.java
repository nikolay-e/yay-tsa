package com.yaytsa.server.infrastructure.fs;

import com.yaytsa.server.util.PathUtils;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JAudioTaggerExtractor {

  private static final int DEFAULT_CHANNELS = 2;
  private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

  static {
    java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
  }

  public Optional<AudioMetadata> extract(Path filePath) {
    try {
      AudioFile audioFile = AudioFileIO.read(filePath.toFile());
      AudioHeader header = audioFile.getAudioHeader();
      Tag tag = audioFile.getTag();

      String title = repairField(getTagValue(tag, FieldKey.TITLE));
      if (title == null || title.isBlank()) {
        title = stripTrackNumberPrefix(PathUtils.getFilenameWithoutExtension(filePath));
      }

      String artist = repairField(getTagValue(tag, FieldKey.ARTIST));
      String albumArtist = repairField(getTagValue(tag, FieldKey.ALBUM_ARTIST));
      if (albumArtist == null || albumArtist.isBlank()) {
        albumArtist = artist;
      }

      String album = repairField(getTagValue(tag, FieldKey.ALBUM));
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

      Double replaygainTrackGain =
          parseReplayGainGain(getRawTagValue(tag, "REPLAYGAIN_TRACK_GAIN"));
      Double replaygainAlbumGain =
          parseReplayGainGain(getRawTagValue(tag, "REPLAYGAIN_ALBUM_GAIN"));
      Double replaygainTrackPeak =
          parseReplayGainPeak(getRawTagValue(tag, "REPLAYGAIN_TRACK_PEAK"));

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
              artworkMimeType,
              replaygainTrackGain,
              replaygainAlbumGain,
              replaygainTrackPeak));

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
    if (channels == null) {
      return DEFAULT_CHANNELS;
    }
    String lower = channels.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("mono")) return 1;
    if (lower.contains("stereo")) return DEFAULT_CHANNELS;
    try {
      return Integer.parseInt(channels.replaceAll("[^0-9]", ""));
    } catch (Exception e) {
      log.trace("Could not parse channels '{}', defaulting to stereo", channels);
      return DEFAULT_CHANNELS;
    }
  }

  private String getRawTagValue(Tag tag, String fieldId) {
    if (tag == null) return null;
    try {
      String value = tag.getFirst(fieldId);
      return (value != null && !value.isBlank()) ? value.trim() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private Double parseReplayGainGain(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      String cleaned = value.replaceAll("[^0-9.+\\-]", "").trim();
      return cleaned.isEmpty() ? null : Double.parseDouble(cleaned);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Double parseReplayGainPeak(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      return null;
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

  private String repairField(String value) {
    if (value == null) return null;
    String repaired = tryRepairCp1251(value);
    if (isGarbledText(repaired)) return null;
    return repaired;
  }

  private static boolean isGarbledText(String text) {
    if (text == null || text.isBlank()) return false;
    long nonWhitespace = text.chars().filter(c -> !Character.isWhitespace(c)).count();
    if (nonWhitespace == 0) return false;
    long hashCount = text.chars().filter(c -> c == '#').count();
    if (hashCount > nonWhitespace / 2) return true;
    long nonAscii = text.chars().filter(c -> c > 127).count();
    if (nonAscii == 0) return false;
    long garbled = text.chars().filter(c -> c == 0xFFFD || c == '?' || c == '#').count();
    return garbled > text.length() / 2;
  }

  private static String tryRepairCp1251(String text) {
    if (text == null || text.isBlank()) return text;
    if (text.chars().anyMatch(c -> c > 0xFF)) return text;
    try {
      byte[] latin1Bytes = text.getBytes(StandardCharsets.ISO_8859_1);
      int cyrillicCount = 0;
      int totalHighBytes = 0;
      for (byte b : latin1Bytes) {
        int unsigned = b & 0xFF;
        if (unsigned >= 0x80) {
          totalHighBytes++;
          if (unsigned >= 0xC0 && unsigned <= 0xFF) {
            cyrillicCount++;
          }
        }
      }
      if (cyrillicCount == 0 || cyrillicCount < totalHighBytes / 2) return text;
      String repaired = new String(latin1Bytes, WINDOWS_1251);
      long cyrillicChars = repaired.chars().filter(c -> c >= 0x0400 && c <= 0x04FF).count();
      if (cyrillicChars >= repaired.length() / 3) {
        return repaired;
      }
    } catch (Exception e) {
      log.trace("CP1251 repair failed for: {}", text);
    }
    return text;
  }

  private static final java.util.regex.Pattern TRACK_NUMBER_PREFIX =
      java.util.regex.Pattern.compile("^\\d{1,3}\\s*[-–—.]\\s*");

  private static String stripTrackNumberPrefix(String filename) {
    if (filename == null) return null;
    String stripped = TRACK_NUMBER_PREFIX.matcher(filename).replaceFirst("");
    return stripped.isBlank() ? filename : stripped;
  }
}
