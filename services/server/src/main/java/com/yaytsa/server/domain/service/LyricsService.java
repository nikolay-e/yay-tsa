package com.yaytsa.server.domain.service;

import com.yaytsa.server.config.LibraryRootsConfig;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    String diskLyrics = readLyricsFromDisk(track.getItemId());
    if (diskLyrics != null) {
      return diskLyrics;
    }
    return track.getLyrics();
  }

  private String readLyricsFromDisk(UUID trackId) {
    ItemEntity item = itemRepository.findById(trackId).orElse(null);
    if (item == null || item.getPath() == null) {
      return null;
    }

    Path audioFilePath = Paths.get(item.getPath());
    if (!isPathSafe(audioFilePath)) {
      log.warn("Path traversal attempt detected for track {}: {}", trackId, audioFilePath);
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
