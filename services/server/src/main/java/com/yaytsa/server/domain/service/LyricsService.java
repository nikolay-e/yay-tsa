package com.yaytsa.server.domain.service;

import com.yaytsa.server.config.LibraryRootsConfig;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LyricsService {

  private static final Logger log = LoggerFactory.getLogger(LyricsService.class);
  private static final String NEGATIVE_CACHE_MARKER = "[no lyrics found]";

  private final ItemRepository itemRepository;
  private final LibraryRootsConfig libraryRootsConfig;

  public LyricsService(ItemRepository itemRepository, LibraryRootsConfig libraryRootsConfig) {
    this.itemRepository = itemRepository;
    this.libraryRootsConfig = libraryRootsConfig;
  }

  public String getLyrics(AudioTrackEntity track) {
    ItemEntity item = track.getItem();
    if (item == null) {
      item = itemRepository.findById(track.getItemId()).orElse(null);
    }
    String diskLyrics = readLyricsFromDisk(item);
    if (diskLyrics != null) {
      return diskLyrics;
    }
    return track.getLyrics();
  }

  public Map<UUID, String> getLyricsForTracks(Collection<AudioTrackEntity> tracks) {
    Map<UUID, String> result = new HashMap<>();
    for (AudioTrackEntity track : tracks) {
      String lyrics = getLyrics(track);
      if (lyrics != null) {
        result.put(track.getItemId(), lyrics);
      }
    }
    return result;
  }

  private String readLyricsFromDisk(ItemEntity item) {
    if (item == null || item.getPath() == null) {
      return null;
    }

    Path audioFilePath = Paths.get(item.getPath());
    if (!isPathSafe(audioFilePath)) {
      log.warn("Path traversal attempt detected for track {}: {}", item.getId(), audioFilePath);
      return null;
    }

    Path parentDir = audioFilePath.getParent();
    String baseName = getFileNameWithoutExtension(audioFilePath.getFileName().toString());

    Path lyricsDir = parentDir.resolve(".lyrics");
    if (isKaraokeDir(parentDir)) {
      lyricsDir = parentDir.getParent().resolve(".lyrics");
    }

    Path lrcPath = lyricsDir.resolve(baseName + ".lrc");
    if (Files.exists(lrcPath) && isPathSafe(lrcPath)) {
      return readFileContent(lrcPath);
    }

    Path txtPath = lyricsDir.resolve(baseName + ".txt");
    if (Files.exists(txtPath) && isPathSafe(txtPath)) {
      return readFileContent(txtPath);
    }

    return null;
  }

  private boolean isKaraokeDir(Path dir) {
    return dir != null && ".karaoke".equals(dir.getFileName().toString());
  }

  private String readFileContent(Path path) {
    try {
      String content = Files.readString(path);
      if (NEGATIVE_CACHE_MARKER.equals(content.trim())) {
        return null;
      }
      log.debug("Read lyrics from disk: {}", path);
      return content;
    } catch (IOException e) {
      log.warn("Failed to read lyrics file {}: {}", path, e.getMessage());
      return null;
    }
  }

  private String getFileNameWithoutExtension(String fileName) {
    int lastDot = fileName.lastIndexOf('.');
    return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
  }

  private boolean isPathSafe(Path filePath) {
    return libraryRootsConfig.isPathSafe(filePath);
  }
}
