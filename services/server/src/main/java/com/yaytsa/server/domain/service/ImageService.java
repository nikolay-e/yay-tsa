package com.yaytsa.server.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yaytsa.server.dto.ImageParams;
import com.yaytsa.server.infrastructure.persistence.entity.*;
import com.yaytsa.server.infrastructure.persistence.repository.AudioTrackRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ImageRepository;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import net.coobird.thumbnailator.Thumbnails;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImageService {
  private static final Logger logger = LoggerFactory.getLogger(ImageService.class);

  private static final String[] ARTWORK_NAMES = {
    "cover.jpg", "cover.jpeg", "cover.png",
    "folder.jpg", "folder.jpeg", "folder.png",
    "Cover.jpg", "Cover.jpeg", "Cover.png",
    "Folder.jpg", "Folder.jpeg", "Folder.png"
  };

  private final ImageRepository imageRepository;
  private final ItemRepository itemRepository;
  private final AudioTrackRepository audioTrackRepository;
  private final Cache<String, byte[]> imageCache;
  private final Path mediaRootPath;
  private volatile Path realMediaRoot;

  public ImageService(
      ImageRepository imageRepository,
      ItemRepository itemRepository,
      AudioTrackRepository audioTrackRepository,
      @Value("${yaytsa.media.library.roots:/media}") String mediaRoot) {
    this.imageRepository = imageRepository;
    this.itemRepository = itemRepository;
    this.audioTrackRepository = audioTrackRepository;
    this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
    this.imageCache =
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(1))
            .recordStats()
            .build();
    initRealMediaRoot();
  }

  private void initRealMediaRoot() {
    try {
      this.realMediaRoot = mediaRootPath.toRealPath();
      logger.info("Initialized media root path: {}", realMediaRoot);
    } catch (IOException e) {
      logger.warn("Media root path not accessible at startup: {}", mediaRootPath);
      this.realMediaRoot = null;
    }
  }

  public Optional<byte[]> getItemImage(UUID itemId, String imageTypeStr, ImageParams params) {
    try {
      ImageType imageType = ImageType.valueOf(imageTypeStr);
      String cacheKey = params.cacheKey(itemId.toString(), imageTypeStr);

      byte[] cached = imageCache.getIfPresent(cacheKey);
      if (cached != null) {
        logger.debug("Cache hit for image: {}", cacheKey);
        return Optional.of(cached);
      }

      Optional<byte[]> imageData = loadImageData(itemId, imageType);
      if (imageData.isEmpty()) {
        logger.debug("Image not found: itemId={}, type={}", itemId, imageType);
        return Optional.empty();
      }

      byte[] processedImage = processImage(imageData.get(), params);
      imageCache.put(cacheKey, processedImage);

      return Optional.of(processedImage);

    } catch (IllegalArgumentException e) {
      logger.warn("Invalid image type: itemId={}, type={}", itemId, imageTypeStr);
      return Optional.empty();
    } catch (Exception | NoClassDefFoundError | UnsatisfiedLinkError e) {
      logger.error(
          "Error processing image: itemId={}, type={}, error={}",
          itemId,
          imageTypeStr,
          e.getMessage(),
          e);
      return Optional.empty();
    }
  }

  public Optional<String> getImageTag(UUID itemId, String imageTypeStr) {
    try {
      ImageType imageType = ImageType.valueOf(imageTypeStr);
      return imageRepository.findFirstByItemIdAndType(itemId, imageType).map(this::generateETag);
    } catch (IllegalArgumentException e) {
      logger.warn("Invalid image type: {}", imageTypeStr);
      return Optional.empty();
    }
  }

  public Optional<byte[]> extractAlbumArt(Path audioFilePath) {
    if (!isPathSafe(audioFilePath)) {
      logger.warn("Audio file not accessible: {}", audioFilePath);
      return Optional.empty();
    }

    try {
      AudioFile audioFile = AudioFileIO.read(audioFilePath.toFile());
      Tag tag = audioFile.getTag();

      if (tag != null && tag.getFirstArtwork() != null) {
        Artwork artwork = tag.getFirstArtwork();
        byte[] imageData = artwork.getBinaryData();
        logger.debug(
            "Extracted album art from: {}, size: {} bytes", audioFilePath, imageData.length);
        return Optional.of(imageData);
      }

      logger.debug("No embedded artwork found in: {}", audioFilePath);
      return Optional.empty();

    } catch (Exception e) {
      logger.error("Failed to extract album art from: {}", audioFilePath, e);
      return Optional.empty();
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
      logger.warn("Failed to resolve media root path: {}", e.getMessage());
      return null;
    }
  }

  private boolean isPathSafe(Path path) {
    try {
      Path root = getRealMediaRoot();
      if (root == null) {
        return false;
      }
      Path realPath = path.toRealPath();
      return realPath.startsWith(root);
    } catch (java.nio.file.NoSuchFileException e) {
      logger.debug("Path does not exist: {}", path);
      return false;
    } catch (IOException e) {
      logger.warn("Path validation failed for {}: {}", path, e.getMessage());
      return false;
    }
  }

  private Optional<byte[]> loadImageData(UUID itemId, ImageType imageType) throws IOException {
    Optional<ImageEntity> imageEntity = imageRepository.findFirstByItemIdAndType(itemId, imageType);

    if (imageEntity.isPresent()) {
      Path imagePath = Paths.get(imageEntity.get().getPath()).toAbsolutePath().normalize();

      if (!isPathSafe(imagePath)) {
        logger.error("Path traversal attempt detected for image {}: {}", itemId, imagePath);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
      }

      if (Files.exists(imagePath)) {
        return Optional.of(Files.readAllBytes(imagePath));
      }
      logger.warn("Image file not found on disk: {}", imagePath);
    }

    if (imageType == ImageType.Primary) {
      Optional<ItemEntity> item = itemRepository.findById(itemId);
      if (item.isPresent()) {
        ItemEntity itemEntity = item.get();

        Optional<byte[]> folderArt = findFolderArtwork(itemEntity);
        if (folderArt.isPresent()) {
          return folderArt;
        }

        Optional<byte[]> embeddedArt = extractEmbeddedArtwork(itemEntity);
        if (embeddedArt.isPresent()) {
          return embeddedArt;
        }
      }
    }

    return Optional.empty();
  }

  private Optional<byte[]> findFolderArtwork(ItemEntity item) {
    try {
      Path folder = null;

      if (item.getType() == ItemType.MusicAlbum) {
        var tracks = audioTrackRepository.findByAlbumIdOrderByDiscNoAscTrackNoAsc(item.getId());
        if (!tracks.isEmpty() && tracks.get(0).getItem().getPath() != null) {
          folder = Paths.get(tracks.get(0).getItem().getPath()).getParent();
        }
      } else if (item.getType() == ItemType.MusicArtist) {
        var albums = itemRepository.findAllByParentId(item.getId());
        for (ItemEntity album : albums) {
          var tracks = audioTrackRepository.findByAlbumIdOrderByDiscNoAscTrackNoAsc(album.getId());
          if (!tracks.isEmpty() && tracks.get(0).getItem().getPath() != null) {
            folder = Paths.get(tracks.get(0).getItem().getPath()).getParent().getParent();
            break;
          }
        }
      } else if (item.getPath() != null
          && !item.getPath().startsWith("artist:")
          && !item.getPath().startsWith("album:")) {
        folder = Paths.get(item.getPath()).getParent();
      }

      if (folder != null) {
        for (String artworkName : ARTWORK_NAMES) {
          Path artworkPath = folder.resolve(artworkName);
          if (isPathSafe(artworkPath) && Files.exists(artworkPath)) {
            logger.debug("Found folder artwork at: {}", artworkPath);
            return Optional.of(Files.readAllBytes(artworkPath));
          }
        }
      }
    } catch (Exception e) {
      logger.warn(
          "Failed to find folder artwork: itemId={}, itemType={}, error={}",
          item.getId(),
          item.getType(),
          e.getMessage());
    }
    return Optional.empty();
  }

  private Optional<byte[]> extractEmbeddedArtwork(ItemEntity item) {
    try {
      Path audioFilePath = null;

      if (item.getType() == ItemType.MusicAlbum) {
        var tracks = audioTrackRepository.findByAlbumIdOrderByDiscNoAscTrackNoAsc(item.getId());
        if (!tracks.isEmpty() && tracks.get(0).getItem().getPath() != null) {
          audioFilePath = Paths.get(tracks.get(0).getItem().getPath());
        }
      } else if (item.getType() == ItemType.MusicArtist) {
        var albums = itemRepository.findAllByParentId(item.getId());
        for (ItemEntity album : albums) {
          var tracks = audioTrackRepository.findByAlbumIdOrderByDiscNoAscTrackNoAsc(album.getId());
          if (!tracks.isEmpty() && tracks.get(0).getItem().getPath() != null) {
            audioFilePath = Paths.get(tracks.get(0).getItem().getPath());
            break;
          }
        }
      } else if (item.getPath() != null
          && !item.getPath().startsWith("artist:")
          && !item.getPath().startsWith("album:")) {
        audioFilePath = Paths.get(item.getPath());
      }

      if (audioFilePath != null) {
        return extractAlbumArt(audioFilePath);
      }
    } catch (Exception e) {
      logger.warn(
          "Failed to extract embedded artwork: itemId={}, itemType={}, error={}",
          item.getId(),
          item.getType(),
          e.getMessage());
    }
    return Optional.empty();
  }

  private byte[] processImage(byte[] originalData, ImageParams params) throws IOException {
    if (!params.requiresResize() && "jpeg".equalsIgnoreCase(params.format())) {
      return originalData;
    }

    BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalData));
    if (image == null) {
      throw new IOException("Failed to decode image");
    }

    BufferedImage resized = image;
    if (params.requiresResize()) {
      Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(image);

      if (params.maxWidth() != null && params.maxHeight() != null) {
        builder.size(params.maxWidth(), params.maxHeight());
      } else if (params.maxWidth() != null) {
        builder.width(params.maxWidth());
      } else if (params.maxHeight() != null) {
        builder.height(params.maxHeight());
      }

      resized = builder.asBufferedImage();
    }

    return encodeImage(resized, params.format(), params.quality());
  }

  private byte[] encodeImage(BufferedImage image, String format, int quality) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    String outputFormat = normalizeFormat(format);

    if ("jpg".equals(outputFormat) || "jpeg".equals(outputFormat)) {
      Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
      if (!writers.hasNext()) {
        throw new IOException("No JPEG writer found");
      }

      ImageWriter writer = writers.next();
      ImageWriteParam writeParam = writer.getDefaultWriteParam();
      writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      writeParam.setCompressionQuality(quality / 100f);

      try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
        writer.setOutput(ios);
        writer.write(null, new IIOImage(image, null, null), writeParam);
      } finally {
        writer.dispose();
      }
    } else if ("webp".equals(outputFormat)) {
      try {
        if (!ImageIO.write(image, "webp", outputStream)) {
          logger.warn("WebP encoding failed, falling back to JPEG");
          return encodeImage(image, "jpeg", quality);
        }
      } catch (Exception | NoClassDefFoundError | UnsatisfiedLinkError e) {
        logger.warn("WebP encoder not available ({}), falling back to JPEG", e.getMessage());
        return encodeImage(image, "jpeg", quality);
      }
    } else if ("png".equals(outputFormat)) {
      ImageIO.write(image, "png", outputStream);
    } else {
      throw new IOException("Unsupported format: " + format);
    }

    return outputStream.toByteArray();
  }

  private String normalizeFormat(String format) {
    if (format == null) {
      return "jpeg";
    }
    return switch (format.toLowerCase(java.util.Locale.ROOT)) {
      case "jpg", "jpeg" -> "jpeg";
      case "webp" -> "webp";
      case "png" -> "png";
      default -> "jpeg";
    };
  }

  private String generateETag(ImageEntity image) {
    try {
      String data =
          image.getId()
              + "-"
              + image.getPath()
              + "-"
              + (image.getSizeBytes() != null ? image.getSizeBytes() : "0")
              + "-"
              + (image.getTag() != null ? image.getTag() : "");

      MessageDigest digest = MessageDigest.getInstance("MD5");
      byte[] hash = digest.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "\"" + HexFormat.of().formatHex(hash) + "\"";
    } catch (NoSuchAlgorithmException e) {
      return "\"" + image.getId().toString() + "\"";
    }
  }

  public Cache<String, byte[]> getCache() {
    return imageCache;
  }
}
