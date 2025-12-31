package com.yaytsa.server.infrastructure.fs;

import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaScannerTransactionalService {

  private static final Logger log = LoggerFactory.getLogger(MediaScannerTransactionalService.class);

  private final JAudioTaggerExtractor metadataExtractor;
  private final ItemRepository itemRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final AlbumRepository albumRepository;
  private final ArtistRepository artistRepository;
  private final GenreRepository genreRepository;
  private final ImageRepository imageRepository;

  private final Map<String, UUID> artistIdCache = new ConcurrentHashMap<>();
  private final Map<String, UUID> albumIdCache = new ConcurrentHashMap<>();
  private final Map<String, UUID> genreIdCache = new ConcurrentHashMap<>();

  public MediaScannerTransactionalService(
      JAudioTaggerExtractor metadataExtractor,
      ItemRepository itemRepository,
      AudioTrackRepository audioTrackRepository,
      AlbumRepository albumRepository,
      ArtistRepository artistRepository,
      GenreRepository genreRepository,
      ImageRepository imageRepository) {
    this.metadataExtractor = metadataExtractor;
    this.itemRepository = itemRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.albumRepository = albumRepository;
    this.artistRepository = artistRepository;
    this.genreRepository = genreRepository;
    this.imageRepository = imageRepository;
  }

  @Transactional
  public void createNewTrack(
      Path filePath, String absolutePath, OffsetDateTime mtime, long fileSize, String libraryRoot)
      throws IOException {
    Optional<AudioMetadata> metadataOpt = metadataExtractor.extract(filePath);
    AudioMetadata metadata = metadataOpt.orElseGet(() -> createFallbackMetadata(filePath));

    ItemEntity artistItem = findOrCreateArtist(metadata.albumArtist(), libraryRoot);
    ItemEntity albumItem = findOrCreateAlbum(metadata.album(), artistItem, libraryRoot);

    ItemEntity trackItem = new ItemEntity();
    trackItem.setType(ItemType.AudioTrack);
    trackItem.setName(metadata.title());
    trackItem.setSortName(createSortName(metadata.title()));
    trackItem.setPath(absolutePath);
    trackItem.setContainer(getExtension(filePath));
    trackItem.setSizeBytes(fileSize);
    trackItem.setMtime(mtime);
    trackItem.setLibraryRoot(libraryRoot);
    trackItem.setParent(albumItem);

    trackItem = itemRepository.save(trackItem);

    AudioTrackEntity audioTrack = new AudioTrackEntity();
    audioTrack.setItem(trackItem);
    audioTrack.setAlbum(albumItem);
    audioTrack.setAlbumArtist(artistItem);
    audioTrack.setTrackNumber(metadata.trackNumber());
    audioTrack.setDiscNumber(metadata.discNumber() != null ? metadata.discNumber() : 1);
    audioTrack.setDurationMs(metadata.durationMs());
    audioTrack.setBitrate(metadata.bitrate());
    audioTrack.setSampleRate(metadata.sampleRate());
    audioTrack.setChannels(metadata.channels());
    audioTrack.setYear(metadata.year());
    audioTrack.setCodec(metadata.codec());
    audioTrack.setComment(metadata.comment());
    audioTrack.setLyrics(metadata.lyrics());

    audioTrackRepository.save(audioTrack);

    processGenres(trackItem, metadata.genres());
    processAlbumArtwork(albumItem, filePath, metadata);
    processArtistArtwork(artistItem, filePath);
  }

  @Transactional
  public void updateExistingTrack(
      ItemEntity item, Path filePath, OffsetDateTime mtime, long fileSize, String libraryRoot)
      throws IOException {
    Optional<AudioMetadata> metadataOpt = metadataExtractor.extract(filePath);
    if (metadataOpt.isEmpty()) return;

    AudioMetadata metadata = metadataOpt.get();

    item.setName(metadata.title());
    item.setSortName(createSortName(metadata.title()));
    item.setMtime(mtime);
    item.setSizeBytes(fileSize);
    itemRepository.save(item);

    Optional<AudioTrackEntity> trackOpt = audioTrackRepository.findById(item.getId());
    if (trackOpt.isPresent()) {
      AudioTrackEntity track = trackOpt.get();
      track.setTrackNumber(metadata.trackNumber());
      track.setDiscNumber(metadata.discNumber() != null ? metadata.discNumber() : 1);
      track.setDurationMs(metadata.durationMs());
      track.setBitrate(metadata.bitrate());
      track.setSampleRate(metadata.sampleRate());
      track.setChannels(metadata.channels());
      track.setYear(metadata.year());
      track.setCodec(metadata.codec());
      track.setComment(metadata.comment());
      track.setLyrics(metadata.lyrics());
      audioTrackRepository.save(track);
    }

    processGenres(item, metadata.genres());
  }

  @Transactional
  public int removeDeletedItems(String libraryRoot, Set<String> processedPaths) {
    List<ItemEntity> existingTracks = itemRepository.findAudioTracksByLibraryRoot(libraryRoot);

    int removed = 0;
    for (ItemEntity track : existingTracks) {
      if (!processedPaths.contains(track.getPath())) {
        log.debug("Removing deleted track: {}", track.getPath());
        itemRepository.delete(track);
        removed++;
      }
    }

    return removed;
  }

  private ItemEntity findOrCreateArtist(String artistName, String libraryRoot) {
    final String finalArtistName =
        (artistName == null || artistName.isBlank()) ? "Unknown Artist" : artistName;

    String cacheKey = finalArtistName.toLowerCase(java.util.Locale.ROOT);
    UUID artistId =
        artistIdCache.computeIfAbsent(
            cacheKey,
            key -> {
              Optional<ItemEntity> existing =
                  itemRepository.findByPath(
                      "artist:" + finalArtistName.toLowerCase(java.util.Locale.ROOT));
              if (existing.isPresent()) return existing.get().getId();

              ItemEntity item = new ItemEntity();
              item.setType(ItemType.MusicArtist);
              item.setName(finalArtistName);
              item.setSortName(createSortName(finalArtistName));
              item.setPath("artist:" + finalArtistName.toLowerCase(java.util.Locale.ROOT));
              item.setLibraryRoot(libraryRoot);
              item = itemRepository.save(item);

              ArtistEntity artist = new ArtistEntity();
              artist.setItem(item);
              artistRepository.save(artist);

              return item.getId();
            });

    return itemRepository.findById(artistId).orElseThrow();
  }

  private ItemEntity findOrCreateAlbum(
      String albumName, ItemEntity artistItem, String libraryRoot) {
    final String finalAlbumName =
        (albumName == null || albumName.isBlank()) ? "Unknown Album" : albumName;

    String artistName = artistItem.getName();
    String cacheKey = (artistName + "::" + finalAlbumName).toLowerCase(java.util.Locale.ROOT);

    UUID albumId =
        albumIdCache.computeIfAbsent(
            cacheKey,
            key -> {
              String albumPath =
                  "album:"
                      + artistName.toLowerCase(java.util.Locale.ROOT)
                      + "::"
                      + finalAlbumName.toLowerCase(java.util.Locale.ROOT);
              Optional<ItemEntity> existing = itemRepository.findByPath(albumPath);
              if (existing.isPresent()) return existing.get().getId();

              ItemEntity item = new ItemEntity();
              item.setType(ItemType.MusicAlbum);
              item.setName(finalAlbumName);
              item.setSortName(createSortName(finalAlbumName));
              item.setPath(albumPath);
              item.setLibraryRoot(libraryRoot);
              item.setParent(artistItem);
              item = itemRepository.save(item);

              AlbumEntity album = new AlbumEntity();
              album.setItem(item);
              album.setArtist(artistItem);
              albumRepository.save(album);

              return item.getId();
            });

    return itemRepository.findById(albumId).orElseThrow();
  }

  private void processGenres(ItemEntity item, List<String> genres) {
    if (genres == null || genres.isEmpty()) return;

    for (String genreName : genres) {
      UUID genreId =
          genreIdCache.computeIfAbsent(
              genreName.toLowerCase(java.util.Locale.ROOT),
              key -> {
                Optional<GenreEntity> existing = genreRepository.findByName(genreName);
                if (existing.isPresent()) return existing.get().getId();

                GenreEntity newGenre = new GenreEntity();
                newGenre.setName(genreName);
                return genreRepository.save(newGenre).getId();
              });

      GenreEntity genre = genreRepository.findById(genreId).orElseThrow();

      boolean exists =
          item.getItemGenres().stream().anyMatch(ig -> ig.getGenre().getId().equals(genre.getId()));

      if (!exists) {
        ItemGenreEntity.ItemGenreId id =
            new ItemGenreEntity.ItemGenreId(item.getId(), genre.getId());
        ItemGenreEntity itemGenre = new ItemGenreEntity();
        itemGenre.setId(id);
        itemGenre.setItem(item);
        itemGenre.setGenre(genre);
        item.getItemGenres().add(itemGenre);
      }
    }
    itemRepository.save(item);
  }

  private void processAlbumArtwork(ItemEntity albumItem, Path trackPath, AudioMetadata metadata) {
    boolean hasArtwork =
        albumItem.getImages().stream().anyMatch(img -> img.getType() == ImageType.Primary);
    if (hasArtwork) return;

    Path albumFolder = trackPath.getParent();
    Optional<Path> folderArtwork = findFolderArtwork(albumFolder);

    if (folderArtwork.isPresent()) {
      saveFolderArtwork(albumItem, folderArtwork.get());
    }
  }

  private static final String[] ARTWORK_NAMES = {
    "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png",
    "Cover.jpg", "Cover.jpeg", "Cover.png", "Folder.jpg", "Folder.jpeg", "Folder.png"
  };

  private static final String[] IMAGE_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"
  };

  private Optional<Path> findFolderArtwork(Path folder) {
    if (folder == null || !Files.isDirectory(folder)) {
      return Optional.empty();
    }

    for (String name : ARTWORK_NAMES) {
      Path artworkPath = folder.resolve(name);
      if (Files.exists(artworkPath)) {
        return Optional.of(artworkPath);
      }
    }

    try (var stream = Files.newDirectoryStream(folder)) {
      for (Path file : stream) {
        if (Files.isRegularFile(file) && isImageFile(file)) {
          return Optional.of(file);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to scan folder for images: {}", folder);
    }
    return Optional.empty();
  }

  private boolean isImageFile(Path file) {
    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    for (String ext : IMAGE_EXTENSIONS) {
      if (name.endsWith(ext)) {
        return true;
      }
    }
    return false;
  }

  private void saveFolderArtwork(ItemEntity albumItem, Path artworkPath) {
    try {
      byte[] artworkBytes = Files.readAllBytes(artworkPath);
      String hash = computeHash(artworkBytes);

      ImageEntity image = new ImageEntity();
      image.setItem(albumItem);
      image.setType(ImageType.Primary);
      image.setPath(artworkPath.toAbsolutePath().toString());
      image.setSizeBytes((long) artworkBytes.length);
      image.setTag(hash);
      image.setIsPrimary(true);
      imageRepository.save(image);

      log.debug("Found folder artwork for album: {} at {}", albumItem.getName(), artworkPath);
    } catch (Exception e) {
      log.warn(
          "Failed to save folder artwork for album {}: {}", albumItem.getName(), e.getMessage());
    }
  }

  private void processArtistArtwork(ItemEntity artistItem, Path trackPath) {
    boolean hasArtwork =
        artistItem.getImages().stream().anyMatch(img -> img.getType() == ImageType.Primary);
    if (hasArtwork) return;

    Path artistFolder = trackPath.getParent().getParent();
    Optional<Path> folderArtwork = findFolderArtwork(artistFolder);

    if (folderArtwork.isPresent()) {
      saveFolderArtwork(artistItem, folderArtwork.get());
    }
  }

  private String getExtension(Path path) {
    String filename = path.getFileName().toString();
    int dotIndex = filename.lastIndexOf('.');
    return dotIndex > 0 ? filename.substring(dotIndex + 1) : "";
  }

  private String createSortName(String name) {
    if (name == null) return "";
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (lower.startsWith("the ")) return name.substring(4);
    if (lower.startsWith("a ")) return name.substring(2);
    if (lower.startsWith("an ")) return name.substring(3);
    return name;
  }

  private AudioMetadata createFallbackMetadata(Path filePath) {
    String filename = filePath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    return new AudioMetadata(
        filename, null, null, null, null, null, null, 0L, 0, 0, 2, "unknown", null, null, List.of(),
        null, null);
  }

  private String computeHash(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] hash = md.digest(data);
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString().substring(0, 8);
    } catch (Exception e) {
      return String.valueOf(data.length);
    }
  }
}
