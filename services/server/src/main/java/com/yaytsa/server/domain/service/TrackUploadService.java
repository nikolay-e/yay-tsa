package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.fs.AudioMetadata;
import com.yaytsa.server.infrastructure.download.YtDlpDownloader;
import com.yaytsa.server.infrastructure.fs.JAudioTaggerExtractor;
import com.yaytsa.server.infrastructure.fs.MediaScannerTransactionalService;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service for handling track uploads from users.
 *
 * <p>Handles: - Metadata extraction - Duplicate detection - File storage with proper Artist/Album
 * structure - Integration with existing scan logic
 */
@Service
public class TrackUploadService {

  private static final Logger log = LoggerFactory.getLogger(TrackUploadService.class);

  private final JAudioTaggerExtractor metadataExtractor;
  private final MediaScannerTransactionalService scannerService;
  private final AudioTrackRepository audioTrackRepository;
  private final ItemRepository itemRepository;
  private final YtDlpDownloader ytDlpDownloader;
  private final com.yaytsa.server.domain.service.metadata.AggregatedMetadataService metadataService;
  private final AudioFingerprintService fingerprintService;
  private final CoverArtService coverArtService;
  private final LyricsFetchService lyricsFetchService;
  private final KaraokeService karaokeService;

  @Value("${yaytsa.media.library.roots}")
  private String libraryRootsConfig;

  public TrackUploadService(
      JAudioTaggerExtractor metadataExtractor,
      MediaScannerTransactionalService scannerService,
      AudioTrackRepository audioTrackRepository,
      ItemRepository itemRepository,
      YtDlpDownloader ytDlpDownloader,
      com.yaytsa.server.domain.service.metadata.AggregatedMetadataService metadataService,
      AudioFingerprintService fingerprintService,
      CoverArtService coverArtService,
      LyricsFetchService lyricsFetchService,
      KaraokeService karaokeService) {
    this.metadataExtractor = metadataExtractor;
    this.scannerService = scannerService;
    this.audioTrackRepository = audioTrackRepository;
    this.itemRepository = itemRepository;
    this.ytDlpDownloader = ytDlpDownloader;
    this.metadataService = metadataService;
    this.fingerprintService = fingerprintService;
    this.coverArtService = coverArtService;
    this.lyricsFetchService = lyricsFetchService;
    this.karaokeService = karaokeService;
  }

  public record UploadResult(
      ItemEntity createdItem,
      AudioTrackEntity existingTrack,
      boolean isDuplicate,
      String artistName,
      String albumName) {

    public static UploadResult success(
        ItemEntity item, String artistName, String albumName) {
      return new UploadResult(item, null, false, artistName, albumName);
    }

    public static UploadResult duplicate(
        AudioTrackEntity existing, String artistName, String albumName) {
      return new UploadResult(null, existing, true, artistName, albumName);
    }
  }

  /**
   * Upload a track from a user.
   *
   * @param file the uploaded file
   * @param userId the user ID (for future permissions/quota)
   * @param libraryRootOverride optional library root override
   * @return upload result with created item or duplicate info
   * @throws IOException if file operations fail
   */
  @Transactional
  public UploadResult uploadTrack(MultipartFile file, UUID userId, String libraryRootOverride)
      throws IOException {

    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null) {
      throw new IllegalArgumentException("Filename is required");
    }

    // Save to temporary file for metadata extraction
    Path tempFile = Files.createTempFile("upload-", "-" + originalFilename);
    try {
      file.transferTo(tempFile);

      // Extract metadata
      AudioMetadata metadata = metadataExtractor.extract(tempFile)
          .orElseThrow(() -> new IllegalArgumentException(
              "Failed to extract metadata from file: " + originalFilename));

      log.info(
          "Extracted metadata: {} - {} (Album: {}, Artist: {})",
          metadata.title(),
          metadata.artist(),
          metadata.album(),
          metadata.albumArtist());

      // Generate audio fingerprint for duplicate detection
      var fingerprintOpt = fingerprintService.generateFingerprint(tempFile);

      if (fingerprintOpt.isPresent()) {
        log.info("Generated audio fingerprint (duration: {}s)", fingerprintOpt.get().duration());

        // Check all existing tracks for acoustic duplicates
        var allTracks = audioTrackRepository.findAll();
        for (var existingTrack : allTracks) {
          if (existingTrack.getFingerprint() != null) {
            var existingFingerprint = new AudioFingerprintService.AudioFingerprint(
                existingTrack.getFingerprint(),
                existingTrack.getFingerprintDuration(),
                existingTrack.getFingerprintSampleRate()
            );

            var duplicateCheck = fingerprintService.checkDuplicate(fingerprintOpt.get(), existingFingerprint);

            if (duplicateCheck.isDuplicate()) {
              log.warn("Acoustic duplicate detected: {} (similarity: {:.2f}%)",
                  existingTrack.getItem().getName(), duplicateCheck.similarity() * 100);
              return UploadResult.duplicate(existingTrack,
                  existingTrack.getAlbumArtist() != null ? existingTrack.getAlbumArtist().getName() : "Unknown",
                  existingTrack.getAlbum() != null ? existingTrack.getAlbum().getName() : "Unknown");
            } else if (!"original".equals(duplicateCheck.variant())) {
              log.info("Variant detected: {} of existing track {}",
                  duplicateCheck.variant(), existingTrack.getItem().getName());
              // Will add suffix to title below
            }
          }
        }
      }

      // Use metadata or fallback to filename
      String title = metadata.title() != null ? metadata.title() : getFilenameWithoutExtension(originalFilename);
      String artist = getArtistName(metadata);
      String album = metadata.album();
      String coverArtUrl = null;
      String artistImageUrl = null;
      String lyrics = null;

      // Try to enrich metadata if album is missing
      if ((album == null || album.isBlank()) && title != null && artist != null) {
        log.info("Album metadata missing, querying metadata providers...");
        var enriched = metadataService.enrichMetadata(artist, title);

        if (enriched.isPresent()) {
          album = enriched.get().album();
          coverArtUrl = enriched.get().coverArtUrl();
          artistImageUrl = enriched.get().artistImageUrl();
          lyrics = enriched.get().lyrics();
          // Use enriched artist if different (handles "Various Artists" cases)
          if (!artist.equalsIgnoreCase(enriched.get().artist())) {
            log.info("Using enriched artist: {} -> {}", artist, enriched.get().artist());
            artist = enriched.get().artist();
          }
          log.info(
              "Metadata enriched from {}: Album = {}, Year = {}, Cover: {}, Artist image: {}, Lyrics: {}",
              enriched.get().source(),
              album,
              enriched.get().year(),
              coverArtUrl != null ? "available" : "none",
              artistImageUrl != null ? "available" : "none",
              lyrics != null ? lyrics.length() + " chars" : "none");
        } else {
          log.warn("No metadata providers found info for {} - {}, using Unknown Album", artist, title);
          album = "Unknown Album";
        }
      } else if (album == null || album.isBlank()) {
        album = "Unknown Album";
      }

      // Sanitize names for filesystem
      String sanitizedArtist = sanitizeFilename(artist);
      String sanitizedAlbum = sanitizeFilename(album);
      String sanitizedTitle = sanitizeFilename(title);

      // Determine library root
      String libraryRoot = getLibraryRoot(libraryRootOverride);

      // Build target path: libraryRoot/Artist/Album/Track.ext
      String extension = getFileExtension(originalFilename);
      Path targetDir = Paths.get(libraryRoot, sanitizedArtist, sanitizedAlbum);
      Path targetFile = targetDir.resolve(sanitizedTitle + "." + extension);

      // Create target directory if needed
      Files.createDirectories(targetDir);

      // Check if file already exists at target path
      if (Files.exists(targetFile)) {
        log.warn("File already exists at path: {}", targetFile);

        // Try to find existing track in database
        var existingItem = itemRepository.findByPath(targetFile.toString());
        if (existingItem.isPresent()) {
          log.warn("Duplicate file found in database: {}", existingItem.get().getId());
          // Find associated audio track
          var audioTrack = audioTrackRepository.findById(existingItem.get().getId());
          if (audioTrack.isPresent()) {
            return UploadResult.duplicate(audioTrack.get(), artist, album);
          }
        }
      }

      // Move file to target location
      Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
      log.info("File moved to: {}", targetFile);

      // Write enriched metadata back to file tags so scanner picks them up
      writeMetadataToFile(targetFile, artist, album, title);

      // Create track in database using existing scanner service
      ItemEntity createdItem = scannerService.createNewTrack(
              targetFile,
              targetFile.toString(),
              OffsetDateTime.now(),
              Files.size(targetFile),
              libraryRoot);

      log.info(
          "Track created in database: {} (ID: {})", createdItem.getName(), createdItem.getId());

      // Save cover art for album (not track)
      ItemEntity albumItem = createdItem.getParent();
      if (albumItem != null) {
        saveCoverArt(albumItem, targetFile, coverArtUrl);
      }

      // Save artist image if available
      if (artistImageUrl != null && !artistImageUrl.isBlank()) {
        var trackEntity = audioTrackRepository.findByIdWithRelations(createdItem.getId());
        if (trackEntity.isPresent() && trackEntity.get().getAlbumArtist() != null) {
          ItemEntity artistItem = trackEntity.get().getAlbumArtist();
          try {
            log.info("Saving artist image from {} for {}", artistImageUrl, artistItem.getName());
            coverArtService.downloadAndSaveArtwork(
                artistItem, artistImageUrl, com.yaytsa.server.infrastructure.persistence.entity.ImageType.Primary, "external");
          } catch (Exception e) {
            log.warn("Failed to save artist image from {}: {}", artistImageUrl, e.getMessage());
          }
        }
      }

      // Save lyrics if available
      if (lyrics != null && !lyrics.isBlank()) {
        saveLyrics(targetFile, lyrics);
      }

      // Asynchronously fetch LRC lyrics and prepare karaoke (non-blocking)
      var trackEntity = audioTrackRepository.findByIdWithRelations(createdItem.getId());
      if (trackEntity.isPresent()) {
        try {
          log.info("Starting background lyrics and karaoke processing for track: {}", createdItem.getName());
          // Fetch synced LRC lyrics from LRCLIB, QQMusic, etc.
          lyricsFetchService.fetchLyricsForTrackAsync(trackEntity.get());
          // Separate vocals for karaoke mode (only if audio-separator is available)
          karaokeService.processTrack(trackEntity.get().getItemId());
        } catch (Exception e) {
          log.warn("Background lyrics/karaoke processing failed (service may be unavailable): {}", e.getMessage());
          // Don't fail upload if lyrics/karaoke fails
        }
      }

      return UploadResult.success(createdItem, artist, album);

    } catch (Exception e) {
      // Clean up temp file on error
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException cleanupEx) {
        log.warn("Failed to clean up temp file: {}", tempFile, cleanupEx);
      }
      throw e;
    }
  }

  private String getArtistName(AudioMetadata metadata) {
    if (metadata.albumArtist() != null && !metadata.albumArtist().isBlank()) {
      return metadata.albumArtist();
    }
    if (metadata.artist() != null && !metadata.artist().isBlank()) {
      return metadata.artist();
    }
    return "Unknown Artist";
  }

  private String getLibraryRoot(String override) {
    if (override != null && !override.isBlank()) {
      return override;
    }

    // Use first configured library root
    String[] roots = libraryRootsConfig.split(",");
    if (roots.length == 0) {
      throw new IllegalStateException("No library roots configured");
    }

    return roots[0].trim();
  }

  private String sanitizeFilename(String name) {
    if (name == null || name.isBlank()) {
      return "Unknown";
    }

    // Remove/replace invalid filesystem characters
    return name.replaceAll("[\\\\/:*?\"<>|]", "_")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String getFileExtension(String filename) {
    int lastDot = filename.lastIndexOf('.');
    return lastDot > 0 ? filename.substring(lastDot + 1) : "mp3";
  }

  private String getFilenameWithoutExtension(String filename) {
    int lastDot = filename.lastIndexOf('.');
    return lastDot > 0 ? filename.substring(0, lastDot) : filename;
  }

  /**
   * Upload a track from a URL (YouTube, SoundCloud, etc.).
   *
   * @param url the URL to download from
   * @param userId the user ID
   * @param libraryRootOverride optional library root override
   * @return upload result with created item or duplicate info
   * @throws IOException if download or file operations fail
   */
  @Transactional
  public UploadResult uploadTrackFromUrl(String url, UUID userId, String libraryRootOverride)
      throws IOException {

    log.info("Starting download from URL: {}", url);

    // Download file using yt-dlp
    Path downloadedFile = ytDlpDownloader.downloadAudio(url);

    try {
      // Extract metadata
      AudioMetadata metadata = metadataExtractor.extract(downloadedFile)
          .orElseThrow(() -> new IllegalArgumentException(
              "Failed to extract metadata from downloaded file"));

      log.info(
          "Extracted metadata: {} - {} (Album: {}, Artist: {})",
          metadata.title(),
          metadata.artist(),
          metadata.album(),
          metadata.albumArtist());

      // Use metadata or fallback to filename
      String title = metadata.title() != null ? metadata.title() : getFilenameWithoutExtension(downloadedFile.getFileName().toString());
      String artist = getArtistName(metadata);
      String album = metadata.album();
      String coverArtUrl = null;
      String artistImageUrl = null;
      String lyrics = null;

      // Try to enrich metadata if album is missing
      if ((album == null || album.isBlank()) && title != null && artist != null) {
        log.info("Album metadata missing, querying metadata providers...");
        var enriched = metadataService.enrichMetadata(artist, title);

        if (enriched.isPresent()) {
          album = enriched.get().album();
          coverArtUrl = enriched.get().coverArtUrl();
          artistImageUrl = enriched.get().artistImageUrl();
          lyrics = enriched.get().lyrics();
          // Use enriched artist if different (handles "Various Artists" cases)
          if (!artist.equalsIgnoreCase(enriched.get().artist())) {
            log.info("Using enriched artist: {} -> {}", artist, enriched.get().artist());
            artist = enriched.get().artist();
          }
          log.info(
              "Metadata enriched from {}: Album = {}, Year = {}, Cover: {}, Artist image: {}, Lyrics: {}",
              enriched.get().source(),
              album,
              enriched.get().year(),
              coverArtUrl != null ? "available" : "none",
              artistImageUrl != null ? "available" : "none",
              lyrics != null ? lyrics.length() + " chars" : "none");
        } else {
          log.warn("No metadata providers found info for {} - {}, using Unknown Album", artist, title);
          album = "Unknown Album";
        }
      } else if (album == null || album.isBlank()) {
        album = "Unknown Album";
      }

      // Sanitize names for filesystem
      String sanitizedArtist = sanitizeFilename(artist);
      String sanitizedAlbum = sanitizeFilename(album);
      String sanitizedTitle = sanitizeFilename(title);

      // Determine library root
      String libraryRoot = getLibraryRoot(libraryRootOverride);

      // Build target path: libraryRoot/Artist/Album/Track.ext
      String extension = getFileExtension(downloadedFile.getFileName().toString());
      Path targetDir = Paths.get(libraryRoot, sanitizedArtist, sanitizedAlbum);
      Path targetFile = targetDir.resolve(sanitizedTitle + "." + extension);

      // Create target directory if needed
      Files.createDirectories(targetDir);

      // Check if file already exists at target path
      if (Files.exists(targetFile)) {
        log.warn("File already exists at path: {}", targetFile);

        // Try to find existing track in database
        var existingItem = itemRepository.findByPath(targetFile.toString());
        if (existingItem.isPresent()) {
          log.warn("Duplicate file found in database: {}", existingItem.get().getId());
          // Find associated audio track
          var audioTrack = audioTrackRepository.findById(existingItem.get().getId());
          if (audioTrack.isPresent()) {
            return UploadResult.duplicate(audioTrack.get(), artist, album);
          }
        }
      }

      // Move file to target location
      Files.move(downloadedFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
      log.info("File moved to: {}", targetFile);

      // Write enriched metadata back to file tags so scanner picks them up
      writeMetadataToFile(targetFile, artist, album, title);

      // Create track in database using existing scanner service
      ItemEntity createdItem = scannerService.createNewTrack(
              targetFile,
              targetFile.toString(),
              OffsetDateTime.now(),
              Files.size(targetFile),
              libraryRoot);

      log.info(
          "Track created in database: {} (ID: {})", createdItem.getName(), createdItem.getId());

      // Save cover art for album (not track)
      ItemEntity albumItem = createdItem.getParent();
      if (albumItem != null) {
        saveCoverArt(albumItem, targetFile, coverArtUrl);
      }

      // Save artist image if available
      if (artistImageUrl != null && !artistImageUrl.isBlank()) {
        var trackEntity = audioTrackRepository.findByIdWithRelations(createdItem.getId());
        if (trackEntity.isPresent() && trackEntity.get().getAlbumArtist() != null) {
          ItemEntity artistItem = trackEntity.get().getAlbumArtist();
          try {
            log.info("Saving artist image from {} for {}", artistImageUrl, artistItem.getName());
            coverArtService.downloadAndSaveArtwork(
                artistItem, artistImageUrl, com.yaytsa.server.infrastructure.persistence.entity.ImageType.Primary, "external");
          } catch (Exception e) {
            log.warn("Failed to save artist image from {}: {}", artistImageUrl, e.getMessage());
          }
        }
      }

      // Save lyrics if available
      if (lyrics != null && !lyrics.isBlank()) {
        saveLyrics(targetFile, lyrics);
      }

      // Asynchronously fetch LRC lyrics and prepare karaoke (non-blocking)
      var trackEntity = audioTrackRepository.findByIdWithRelations(createdItem.getId());
      if (trackEntity.isPresent()) {
        try {
          log.info("Starting background lyrics and karaoke processing for track: {}", createdItem.getName());
          // Fetch synced LRC lyrics from LRCLIB, QQMusic, etc.
          lyricsFetchService.fetchLyricsForTrackAsync(trackEntity.get());
          // Separate vocals for karaoke mode (only if audio-separator is available)
          karaokeService.processTrack(trackEntity.get().getItemId());
        } catch (Exception e) {
          log.warn("Background lyrics/karaoke processing failed (service may be unavailable): {}", e.getMessage());
          // Don't fail upload if lyrics/karaoke fails
        }
      }

      return UploadResult.success(createdItem, artist, album);

    } finally {
      // Clean up downloaded file and temp directory
      try {
        Path tempDir = downloadedFile.getParent();
        Files.deleteIfExists(downloadedFile);
        Files.deleteIfExists(tempDir);
      } catch (IOException cleanupEx) {
        log.warn("Failed to clean up downloaded file: {}", downloadedFile, cleanupEx);
      }
    }
  }

  /**
   * Save cover art for a track from embedded artwork and external URLs.
   *
   * @param item the created item entity
   * @param audioFile path to the audio file
   * @param coverArtUrl optional external cover art URL (from Genius, etc.)
   */
  private void saveCoverArt(ItemEntity item, Path audioFile, String coverArtUrl) {
    try {
      // Try embedded artwork first
      boolean embeddedSaved = coverArtService.saveEmbeddedArtwork(item, audioFile);

      // If no embedded artwork and external URL available, download it
      if (!embeddedSaved && coverArtUrl != null && !coverArtUrl.isBlank()) {
        log.info("Downloading cover art from external source for item: {}", item.getId());
        coverArtService.downloadAndSaveArtwork(
            item, coverArtUrl, com.yaytsa.server.infrastructure.persistence.entity.ImageType.Primary, "external");
      }

    } catch (Exception e) {
      log.warn("Failed to save cover art for item: {}, continuing without artwork", item.getId(), e);
      // Don't fail the upload if artwork fails
    }
  }

  private void saveLyrics(Path audioFile, String lyrics) {
    try {
      Path parentDir = audioFile.getParent();
      Path lyricsDir = parentDir.resolve(".lyrics");

      // Create .lyrics directory if it doesn't exist
      Files.createDirectories(lyricsDir);

      // Save lyrics with same base name as audio file but .txt extension
      String baseName = audioFile.getFileName().toString().replaceFirst("\\.[^.]+$", "");
      Path lyricsPath = lyricsDir.resolve(baseName + ".txt");

      // Validate lyrics before saving
      if (!isValidLyrics(lyrics)) {
        log.warn("Lyrics validation failed for {}, skipping save", audioFile.getFileName());
        // Write negative cache marker
        Path lyricsCacheDir = parentDir.resolve(".lyrics-cache");
        Files.createDirectories(lyricsCacheDir);
        Path negativeCachePath = lyricsCacheDir.resolve(baseName + ".notfound");
        Files.writeString(negativeCachePath, "[no lyrics found]", StandardCharsets.UTF_8);
        return;
      }

      Files.writeString(lyricsPath, lyrics, StandardCharsets.UTF_8);
      log.info("Saved lyrics to: {} ({} chars, {} lines)", lyricsPath, lyrics.length(), lyrics.split("\n").length);

    } catch (Exception e) {
      log.warn("Failed to save lyrics for {}: {}", audioFile, e.getMessage());
      // Don't fail the upload if lyrics saving fails
    }
  }

  private boolean isValidLyrics(String lyrics) {
    if (lyrics == null || lyrics.isBlank()) {
      return false;
    }

    // Check minimum length
    if (lyrics.length() < 50) {
      log.debug("Lyrics too short: {} chars", lyrics.length());
      return false;
    }

    // Check line count
    String[] lines = lyrics.split("\n");
    long contentLines = java.util.Arrays.stream(lines)
        .filter(line -> !line.trim().isEmpty())
        .filter(line -> !line.trim().startsWith("[")) // Skip LRC metadata
        .count();

    if (contentLines < 2) {
      log.debug("Too few lyric lines: {}", contentLines);
      return false;
    }

    if (contentLines > 2000) {
      log.debug("Too many lyric lines: {}", contentLines);
      return false;
    }

    // Check for HTML content
    String lowerContent = lyrics.toLowerCase();
    if (lowerContent.contains("<html") || lowerContent.contains("<body") || lowerContent.contains("<div")) {
      log.debug("Lyrics contain HTML tags");
      return false;
    }

    // Check for JavaScript/code indicators
    if (lowerContent.contains("function ") || lowerContent.contains("var ") ||
        lowerContent.contains("document.") || lowerContent.contains("window.")) {
      log.debug("Lyrics contain code indicators");
      return false;
    }

    return true;
  }

  /**
   * Write enriched metadata back to audio file tags.
   *
   * @param audioFile path to the audio file
   * @param artist artist name
   * @param album album name
   * @param title track title
   */
  private void writeMetadataToFile(Path audioFile, String artist, String album, String title) {
    try {
      org.jaudiotagger.audio.AudioFile f = org.jaudiotagger.audio.AudioFileIO.read(audioFile.toFile());
      org.jaudiotagger.tag.Tag tag = f.getTagOrCreateAndSetDefault();

      if (album != null && !album.equals("Unknown Album")) {
        tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, album);
      }
      if (artist != null) {
        tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, artist);
        tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, artist);
      }
      if (title != null) {
        tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, title);
      }

      f.commit();
      log.info("Updated file tags: album={}, artist={}", album, artist);
    } catch (Exception e) {
      log.warn("Failed to write metadata to file: {}, continuing anyway", audioFile, e);
      // Don't fail the upload if tag writing fails
    }
  }
}
