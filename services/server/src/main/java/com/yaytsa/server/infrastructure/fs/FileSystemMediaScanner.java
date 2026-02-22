package com.yaytsa.server.infrastructure.fs;

import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.*;
import com.yaytsa.server.infrastructure.transcoding.FfmpegTranscoder;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileSystemMediaScanner {

  private static final Logger log = LoggerFactory.getLogger(FileSystemMediaScanner.class);

  private final ItemRepository itemRepository;
  private final MediaScannerTransactionalService transactionalService;
  private final FfmpegTranscoder transcoder;

  @Value("${yaytsa.media.library.supported-extensions:mp3,flac,m4a,aac,ogg,opus,wav,wma}")
  private String supportedExtensions;

  @Value("${yaytsa.media.library.ignored-folders:.git,.svn,node_modules,@eaDir}")
  private String ignoredFolders;

  @Value("${yaytsa.media.library.scan-threads:8}")
  private int scanThreads;

  public FileSystemMediaScanner(
      ItemRepository itemRepository,
      MediaScannerTransactionalService transactionalService,
      FfmpegTranscoder transcoder) {
    this.itemRepository = itemRepository;
    this.transactionalService = transactionalService;
    this.transcoder = transcoder;
  }

  public record ScanResult(
      int filesScanned, int filesAdded, int filesUpdated, int filesRemoved, int errors) {}

  public ScanResult scan(String libraryRoot) {
    log.info("Starting library scan for: {}", libraryRoot);

    Path rootPath = Path.of(libraryRoot);
    if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
      log.error("Library root does not exist or is not a directory: {}", libraryRoot);
      return new ScanResult(0, 0, 0, 0, 1);
    }

    Set<String> extensions = parseExtensions();
    Set<String> ignoredFolderSet = parseIgnoredFolders();

    List<Path> audioFiles = collectAudioFiles(rootPath, extensions, ignoredFolderSet);
    log.info("Found {} audio files to process", audioFiles.size());

    AtomicInteger added = new AtomicInteger(0);
    AtomicInteger updated = new AtomicInteger(0);
    AtomicInteger errors = new AtomicInteger(0);
    Set<String> processedPaths = new HashSet<>();

    for (Path file : audioFiles) {
      processAudioFile(file, libraryRoot, added, updated, errors, processedPaths);
    }

    int removed = transactionalService.removeDeletedItems(libraryRoot, processedPaths);

    int transcoded = transcodeNonNativeFiles(processedPaths);

    int artworkFound = transactionalService.scanMissingArtwork();
    log.info("Found {} missing artwork files during scan", artworkFound);

    log.info(
        "Scan completed: {} scanned, {} added, {} updated, {} removed, {} transcoded, {} errors",
        audioFiles.size(),
        added.get(),
        updated.get(),
        removed,
        transcoded,
        errors.get());

    return new ScanResult(audioFiles.size(), added.get(), updated.get(), removed, errors.get());
  }

  private void processAudioFile(
      Path filePath,
      String libraryRoot,
      AtomicInteger added,
      AtomicInteger updated,
      AtomicInteger errors,
      Set<String> processedPaths) {
    String absolutePath = filePath.toAbsolutePath().toString();
    processedPaths.add(absolutePath);

    try {
      BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
      OffsetDateTime mtime =
          OffsetDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneOffset.UTC);
      long fileSize = attrs.size();

      Optional<ItemEntity> existingItem = itemRepository.findByPath(absolutePath);

      if (existingItem.isPresent()) {
        ItemEntity item = existingItem.get();
        if (item.getMtime() != null
            && item.getMtime().equals(mtime)
            && item.getSizeBytes() != null
            && item.getSizeBytes().equals(fileSize)) {
          return;
        }
        transactionalService.updateExistingTrack(item, filePath, mtime, fileSize, libraryRoot);
        updated.incrementAndGet();
      } else {
        transactionalService.createNewTrack(filePath, absolutePath, mtime, fileSize, libraryRoot);
        added.incrementAndGet();
      }
    } catch (Exception e) {
      log.error("Error processing file {}: {}", filePath, e.getMessage());
      errors.incrementAndGet();
    }
  }

  private int transcodeNonNativeFiles(Set<String> processedPaths) {
    var tracks = transactionalService.findNonNativeCodecTracks();
    if (tracks.isEmpty()) {
      return 0;
    }

    log.info("Found {} tracks with non-native codecs to transcode", tracks.size());
    int transcoded = 0;

    for (var track : tracks) {
      Path inputPath = Path.of(track.filePath());
      if (!Files.exists(inputPath)) {
        log.warn("Skipping transcode for missing file: {}", track.filePath());
        continue;
      }

      var outputPath = transcoder.transcodeToFlac(inputPath);
      if (outputPath.isPresent()) {
        if (!transcoder.validateTranscodedOutput(inputPath, outputPath.get())) {
          log.error("Transcoded output failed validation, keeping original: {}", inputPath);
          try {
            Files.deleteIfExists(outputPath.get());
          } catch (IOException e) {
            log.warn("Failed to delete invalid transcode output: {}", e.getMessage());
          }
          continue;
        }

        transactionalService.updateTranscodedTrack(track.itemId(), outputPath.get());

        processedPaths.remove(track.filePath());
        processedPaths.add(outputPath.get().toAbsolutePath().toString());

        try {
          Files.deleteIfExists(inputPath);
          log.info("Deleted original file after transcode: {}", inputPath.getFileName());
        } catch (IOException e) {
          log.warn("Failed to delete original file {}: {}", inputPath, e.getMessage());
        }

        transcoded++;
      }
    }

    log.info("Transcoded {} of {} non-native codec files to FLAC", transcoded, tracks.size());
    return transcoded;
  }

  private List<Path> collectAudioFiles(
      Path root, Set<String> extensions, Set<String> ignoredFolders) {
    List<Path> files = new ArrayList<>();
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String dirName = dir.getFileName().toString();
              if (ignoredFolders.contains(dirName)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String extension = getExtension(file).toLowerCase(java.util.Locale.ROOT);
              if (extensions.contains(extension)) {
                files.add(file);
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              log.warn("Failed to access file: {}", file, exc);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      log.error("Error walking directory tree: {}", e.getMessage());
    }
    return files;
  }

  private Set<String> parseExtensions() {
    return new HashSet<>(
        Arrays.asList(supportedExtensions.toLowerCase(java.util.Locale.ROOT).split(",")));
  }

  private Set<String> parseIgnoredFolders() {
    return new HashSet<>(Arrays.asList(ignoredFolders.split(",")));
  }

  private String getExtension(Path path) {
    String filename = path.getFileName().toString();
    int dotIndex = filename.lastIndexOf('.');
    return dotIndex > 0 ? filename.substring(dotIndex + 1) : "";
  }
}
