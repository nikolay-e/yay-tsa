package com.yaytsa.server.domain.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LyricsService {

  private static final Logger log = LoggerFactory.getLogger(LyricsService.class);

  private final ItemRepository itemRepository;
  private final Path mediaRootPath;
  private volatile Path realMediaRoot;

  public LyricsService(
      ItemRepository itemRepository,
      @Value("${yaytsa.media.library.roots:/media}") String mediaRoot) {
    this.itemRepository = itemRepository;
    this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
    initRealMediaRoot();
  }

  private void initRealMediaRoot() {
    try {
      this.realMediaRoot = mediaRootPath.toRealPath();
      log.info("Initialized media root path: {}", realMediaRoot);
    } catch (IOException e) {
      log.warn("Media root path not accessible at startup: {}", mediaRootPath);
      this.realMediaRoot = null;
    }
  }

  private synchronized Path getRealMediaRoot() {
    if (realMediaRoot != null) {
      return realMediaRoot;
    }
    try {
      realMediaRoot = mediaRootPath.toRealPath();
      return realMediaRoot;
    } catch (IOException e) {
      log.warn("Failed to resolve media root path: {}", e.getMessage());
      return null;
    }
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
    try {
      Path root = getRealMediaRoot();
      if (root == null) {
        return false;
      }
      Path realPath = filePath.toRealPath();
      return realPath.startsWith(root);
    } catch (java.nio.file.NoSuchFileException e) {
      log.debug("Path does not exist: {}", filePath);
      return false;
    } catch (IOException e) {
      log.warn("Path validation failed for {}: {}", filePath, e.getMessage());
      return false;
    }
  }
}
