package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.fs.AudioMetadata;
import com.yaytsa.server.infrastructure.fs.JAudioTaggerExtractor;
import com.yaytsa.server.infrastructure.fs.MediaScannerTransactionalService;
import com.yaytsa.server.infrastructure.persistence.entity.AlbumEntity;
import com.yaytsa.server.infrastructure.persistence.entity.AudioTrackEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.AlbumRepository;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ImageRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import java.io.IOException;
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

@Service
public class TrackUploadService {

  private static final Logger log = LoggerFactory.getLogger(TrackUploadService.class);

  private final JAudioTaggerExtractor metadataExtractor;
  private final MediaScannerTransactionalService scannerService;
  private final AudioTrackRepository audioTrackRepository;
  private final AlbumRepository albumRepository;
  private final ItemRepository itemRepository;
  private final ImageRepository imageRepository;
  private final com.yaytsa.server.domain.service.metadata.AggregatedMetadataService metadataService;
  private final CoverArtService coverArtService;
  private final KaraokeService karaokeService;

  @Value("${yaytsa.media.upload-library-root:/app/uploads}")
  private String uploadLibraryRoot;

  @Value("${yaytsa.media.library.roots}")
  private String libraryRootsConfig;

  public TrackUploadService(
      JAudioTaggerExtractor metadataExtractor,
      MediaScannerTransactionalService scannerService,
      AudioTrackRepository audioTrackRepository,
      AlbumRepository albumRepository,
      ItemRepository itemRepository,
      ImageRepository imageRepository,
      com.yaytsa.server.domain.service.metadata.AggregatedMetadataService metadataService,
      CoverArtService coverArtService,
      KaraokeService karaokeService) {
    this.metadataExtractor = metadataExtractor;
    this.scannerService = scannerService;
    this.audioTrackRepository = audioTrackRepository;
    this.albumRepository = albumRepository;
    this.itemRepository = itemRepository;
    this.imageRepository = imageRepository;
    this.metadataService = metadataService;
    this.coverArtService = coverArtService;
    this.karaokeService = karaokeService;
  }

  public record UploadResult(
      ItemEntity createdItem,
      AudioTrackEntity existingTrack,
      boolean isDuplicate,
      String artistName,
      String albumName,
      boolean albumComplete,
      Integer albumTotalTracks,
      Long albumCurrentTracks) {

    public static UploadResult success(
        ItemEntity item,
        String artistName,
        String albumName,
        boolean albumComplete,
        Integer albumTotalTracks,
        long albumCurrentTracks) {
      return new UploadResult(
          item, null, false, artistName, albumName,
          albumComplete, albumTotalTracks, albumCurrentTracks);
    }

    public static UploadResult duplicate(
        AudioTrackEntity existing, String artistName, String albumName) {
      return new UploadResult(null, existing, true, artistName, albumName, true, null, null);
    }
  }

  public UploadResult uploadTrack(MultipartFile file, UUID userId, String libraryRootOverride)
      throws IOException {

    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null) {
      throw new IllegalArgumentException("Filename is required");
    }

    // Phase 1: Extract metadata and prepare file (no transaction)
    Path tempFile = Files.createTempFile("upload-", "-" + originalFilename);
    try {
      file.transferTo(tempFile);

      AudioMetadata metadata = metadataExtractor.extract(tempFile)
          .orElseThrow(() -> new IllegalArgumentException(
              "Failed to extract metadata from file: " + originalFilename));

      log.info("Extracted metadata: {} - {} (Album: {}, Artist: {})",
          metadata.title(), metadata.artist(), metadata.album(), metadata.albumArtist());

      String title = metadata.title() != null ? metadata.title() : getFilenameWithoutExtension(originalFilename);
      String artist = getArtistName(metadata);
      String album = metadata.album();

      // Check for duplicate by artist + title in DB
      var existingTracks = audioTrackRepository.findByArtistNameAndTitle(artist, title);
      if (!existingTracks.isEmpty()) {
        return UploadResult.duplicate(existingTracks.getFirst(), artist, album != null ? album : "Unknown Album");
      }

      String coverArtUrl = null;
      String artistImageUrl = null;
      Integer enrichedTotalTracks = null;

      // Always enrich metadata from external APIs when we have artist + title.
      // Even if the file already has an album tag, we still want cover art and totalTracks.
      if (title != null && artist != null) {
        log.info("Querying metadata providers for: {} - {}", artist, title);
        var enriched = metadataService.enrichMetadata(artist, title);

        if (enriched.isPresent()) {
          // Use album from file tags if already present; fall back to provider
          if (album == null || album.isBlank()) {
            album = enriched.get().album();
          }
          coverArtUrl = enriched.get().coverArtUrl();
          artistImageUrl = enriched.get().artistImageUrl();
          enrichedTotalTracks = enriched.get().totalTracks();
          if (!artist.equalsIgnoreCase(enriched.get().artist())) {
            artist = enriched.get().artist();
          }
          log.info("Metadata enriched from {}: Album = {}, coverArtUrl = {}, totalTracks = {}",
              enriched.get().source(), album,
              coverArtUrl != null ? "yes" : "no", enrichedTotalTracks);
        } else if (album == null || album.isBlank()) {
          album = "Unknown Album";
        }
      } else if (album == null || album.isBlank()) {
        album = "Unknown Album";
      }

      // Prepare target path — always use the dedicated upload library root
      String sanitizedArtist = sanitizeFilename(artist);
      String sanitizedAlbum = sanitizeFilename(album);
      String sanitizedTitle = sanitizeFilename(title);
      String libraryRoot = getLibraryRoot(libraryRootOverride);
      String extension = getFileExtension(originalFilename);
      Path libraryRootPath = Paths.get(libraryRoot).normalize();
      Path targetDir = libraryRootPath.resolve(sanitizedArtist).resolve(sanitizedAlbum).normalize();
      Path targetFile = targetDir.resolve(sanitizedTitle + "." + extension).normalize();

      if (!targetDir.startsWith(libraryRootPath) || !targetFile.startsWith(libraryRootPath)) {
        throw new IllegalArgumentException("Invalid path: resolved path escapes library root");
      }

      Files.createDirectories(targetDir);

      // Anti-duplication on disk: skip copy if file already exists
      if (!Files.exists(targetFile)) {
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        writeMetadataToFile(targetFile, artist, album, title);
      } else {
        log.info("File already exists at target path, skipping copy: {}", targetFile);
        Files.deleteIfExists(tempFile);
      }

      // Phase 2: DB writes (short transaction)
      ItemEntity createdItem = persistTrack(targetFile, libraryRoot);

      log.info("Track created: {} (ID: {})", createdItem.getName(), createdItem.getId());

      // Phase 3: Update album completion
      boolean albumComplete = true;
      Integer albumTotalTracks = null;
      long albumCurrentTracks = 0;
      try {
        ItemEntity albumItem = createdItem.getParent();
        if (albumItem != null) {
          AlbumEntity albumEntity = albumRepository.findById(albumItem.getId()).orElse(null);
          if (albumEntity != null) {
            albumCurrentTracks = audioTrackRepository.countByAlbumId(albumItem.getId());
            // Use totalTracks from provider if the album doesn't already have it
            if (albumEntity.getTotalTracks() == null && enrichedTotalTracks != null) {
              albumEntity.setTotalTracks(enrichedTotalTracks);
            }
            albumTotalTracks = albumEntity.getTotalTracks();
            albumComplete =
                (albumTotalTracks == null) || (albumCurrentTracks >= albumTotalTracks);
            albumEntity.setIsComplete(albumComplete);
            albumRepository.save(albumEntity);
          }
        }
      } catch (Exception e) {
        log.warn("Failed to update album completion: {}", e.getMessage());
      }

      // Phase 4: Post-upload processing (outside transaction, non-blocking)
      processArtwork(createdItem, targetFile, coverArtUrl);
      processArtistArtwork(createdItem, artistImageUrl);
      processKaraoke(createdItem);

      return UploadResult.success(createdItem, artist, album, albumComplete, albumTotalTracks, albumCurrentTracks);

    } catch (Exception e) {
      try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
      throw e;
    }
  }

  @Transactional
  public ItemEntity persistTrack(Path targetFile, String libraryRoot) throws IOException {
    return scannerService.createNewTrack(
        targetFile, targetFile.toString(), OffsetDateTime.now(),
        Files.size(targetFile), libraryRoot);
  }

  private void processArtwork(ItemEntity createdItem, Path targetFile, String coverArtUrl) {
    try {
      ItemEntity albumItem = createdItem.getParent();
      if (albumItem != null) {
        // First: try embedded artwork in the audio file
        boolean embeddedSaved = coverArtService.saveEmbeddedArtwork(albumItem, targetFile);

        // Second: if no embedded art, try downloading from provider URL (Cover Art Archive, etc.)
        if (!embeddedSaved && coverArtUrl != null && !coverArtUrl.isBlank()) {
          coverArtService.downloadAndSaveArtwork(
              albumItem, coverArtUrl,
              com.yaytsa.server.infrastructure.persistence.entity.ImageType.Primary,
              "metadata-provider");
        }
      }
    } catch (Exception e) {
      log.warn("Artwork processing failed, continuing: {}", e.getMessage());
    }
  }

  private void processArtistArtwork(ItemEntity createdItem, String artistImageUrl) {
    if (artistImageUrl == null || artistImageUrl.isBlank()) {
      return;
    }
    try {
      ItemEntity albumItem = createdItem.getParent();
      if (albumItem == null) return;
      ItemEntity artistItem = albumItem.getParent();
      if (artistItem == null) return;

      // Only download if artist doesn't already have an image
      boolean alreadyHasImage = imageRepository.findFirstByItem_IdAndType(
          artistItem.getId(),
          com.yaytsa.server.infrastructure.persistence.entity.ImageType.Primary).isPresent();
      if (!alreadyHasImage) {
        coverArtService.downloadAndSaveArtwork(
            artistItem, artistImageUrl,
            com.yaytsa.server.infrastructure.persistence.entity.ImageType.Primary,
            "genius-artist");
      }
    } catch (Exception e) {
      log.warn("Artist artwork processing failed, continuing: {}", e.getMessage());
    }
  }

  private void processKaraoke(ItemEntity createdItem) {
    try {
      karaokeService.processTrack(createdItem.getId());
    } catch (Exception e) {
      log.warn("Karaoke processing failed (service may be unavailable): {}", e.getMessage());
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
    return name.replaceAll("[\\\\/:*?\"<>|]", "_")
        .replace("..", "_")
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
    } catch (Exception e) {
      log.warn("Failed to write metadata to file: {}", audioFile, e);
    }
  }
}
