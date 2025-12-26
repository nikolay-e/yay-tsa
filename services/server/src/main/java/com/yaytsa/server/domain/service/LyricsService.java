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

  public LyricsService(
      ItemRepository itemRepository,
      @Value("${yaytsa.media.library.roots:/media}") String mediaRoot) {
    this.itemRepository = itemRepository;
    this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
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

    Path audioFilePath = Paths.get(item.getPath()).toAbsolutePath().normalize();
    if (!audioFilePath.startsWith(mediaRootPath)) {
      return null;
    }

    Path lyricsDir = audioFilePath.getParent().resolve(".lyrics");
    String baseName = getFileNameWithoutExtension(audioFilePath.getFileName().toString());

    Path lrcPath = lyricsDir.resolve(baseName + ".lrc");
    if (Files.exists(lrcPath)) {
      return readFileContent(lrcPath);
    }

    Path txtPath = lyricsDir.resolve(baseName + ".txt");
    if (Files.exists(txtPath)) {
      return readFileContent(txtPath);
    }

    return null;
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
}
