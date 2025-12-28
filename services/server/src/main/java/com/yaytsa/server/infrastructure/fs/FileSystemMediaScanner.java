package com.yaytsa.server.infrastructure.fs;

import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FileSystemMediaScanner {

  private static final Logger log = LoggerFactory.getLogger(FileSystemMediaScanner.class);

  private final JAudioTaggerExtractor metadataExtractor;
  private final ItemRepository itemRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final AlbumRepository albumRepository;
  private final ArtistRepository artistRepository;
  private final GenreRepository genreRepository;
  private final ImageRepository imageRepository;

  @Value("${yaytsa.media.library.supported-extensions:mp3,flac,m4a,aac,ogg,opus,wav,wma}")
  private String supportedExtensions;

  @Value("${yaytsa.media.library.ignored-folders:.git,.svn,node_modules,@eaDir}")
  private String ignoredFolders;

  @Value("${yaytsa.media.library.scan-threads:8}")
  private int scanThreads;

  private final Map<String, UUID> artistIdCache = new ConcurrentHashMap<>();
  private final Map<String, UUID> albumIdCache = new ConcurrentHashMap<>();
  private final Map<String, UUID> genreIdCache = new ConcurrentHashMap<>();

  public FileSystemMediaScanner(
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

  public record ScanResult(
      int filesScanned, int filesAdded, int filesUpdated, int filesRemoved, int errors) {}

  @Transactional
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

    int removed = removeDeletedItems(libraryRoot, processedPaths);

    log.info(
        "Scan completed: {} scanned, {} added, {} updated, {} removed, {} errors",
        audioFiles.size(),
        added.get(),
        updated.get(),
        removed,
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
        updateExistingTrack(item, filePath, mtime, fileSize, libraryRoot);
        updated.incrementAndGet();
      } else {
        createNewTrack(filePath, absolutePath, mtime, fileSize, libraryRoot);
        added.incrementAndGet();
      }
    } catch (Exception e) {
      log.error("Error processing file {}: {}", filePath, e.getMessage());
      errors.incrementAndGet();
    }
  }

  private synchronized void createNewTrack(
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

  private synchronized void updateExistingTrack(
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
    // Note: Embedded artwork is NOT cached to disk during scan.
    // It will be extracted on-demand from audio files by ImageService.
  }

  private Optional<Path> findFolderArtwork(Path folder) {
    String[] artworkNames = {
      "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png"
    };

    for (String name : artworkNames) {
      Path artworkPath = folder.resolve(name);
      if (Files.exists(artworkPath)) {
        return Optional.of(artworkPath);
      }
      Path upperCase =
          folder.resolve(
              name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + name.substring(1));
      if (Files.exists(upperCase)) {
        return Optional.of(upperCase);
      }
    }
    return Optional.empty();
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

  private int removeDeletedItems(String libraryRoot, Set<String> processedPaths) {
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
              log.warn("Failed to access file: {}", file);
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
