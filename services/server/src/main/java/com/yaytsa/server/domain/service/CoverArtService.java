package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.ImageEntity;
import com.yaytsa.server.infrastructure.persistence.entity.ImageType;
import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ImageRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing cover art (album/track artwork).
 *
 * <p>Handles: - Extracting embedded artwork from audio files - Downloading artwork from external
 * APIs (Genius, etc.) - Saving artwork to filesystem and database - Generating image metadata
 * (dimensions, hash)
 */
@Service
public class CoverArtService {

  private static final Logger log = LoggerFactory.getLogger(CoverArtService.class);

  private static final Set<String> ALLOWED_IMAGE_HOSTS = Set.of(
      "images.genius.com",
      "coverartarchive.org",
      "i.scdn.co",
      "lastfm.freetls.fastly.net",
      "upload.wikimedia.org",
      "is1-ssl.mzstatic.com",
      "is2-ssl.mzstatic.com",
      "is3-ssl.mzstatic.com",
      "is4-ssl.mzstatic.com",
      "is5-ssl.mzstatic.com"
  );

  private final ImageService imageService;
  private final ImageRepository imageRepository;
  private final Path imageCacheDirectory;
  private final HttpClient httpClient;

  public CoverArtService(
      ImageService imageService,
      ImageRepository imageRepository,
      @Value("${yaytsa.media.images.cache-directory:./temp/images}") String imageCacheDir) {
    this.imageService = imageService;
    this.imageRepository = imageRepository;
    this.imageCacheDirectory = Paths.get(imageCacheDir).toAbsolutePath().normalize();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    try {
      Files.createDirectories(imageCacheDirectory);
      log.info("Cover art cache directory: {}", imageCacheDirectory.toAbsolutePath());
    } catch (IOException e) {
      log.error("Failed to create image cache directory: {}", imageCacheDirectory, e);
    }
  }

  /**
   * Save cover art for an item from embedded audio file artwork.
   *
   * @param item the item entity
   * @param audioFilePath path to the audio file
   * @return true if artwork was extracted and saved
   */
  @Transactional
  public boolean saveEmbeddedArtwork(ItemEntity item, Path audioFilePath) {
    try {
      Optional<byte[]> artworkData = imageService.extractAlbumArt(audioFilePath);

      if (artworkData.isEmpty()) {
        log.debug("No embedded artwork found in: {}", audioFilePath);
        return false;
      }

      return saveArtworkData(item, artworkData.get(), ImageType.Primary, "embedded");

    } catch (Exception e) {
      log.error("Failed to save embedded artwork for item: {}", item.getId(), e);
      return false;
    }
  }

  /**
   * Download and save cover art from a URL.
   *
   * @param item the item entity
   * @param imageUrl URL of the cover art image
   * @param imageType type of image (Primary, Banner, etc.)
   * @param source source identifier (e.g., "genius", "musicbrainz")
   * @return true if image was downloaded and saved
   */
  @Transactional
  public boolean downloadAndSaveArtwork(
      ItemEntity item, String imageUrl, ImageType imageType, String source) {
    try {
      if (!isAllowedImageUrl(imageUrl)) {
        log.warn("Blocked image download from untrusted URL: {}", imageUrl);
        return false;
      }

      log.debug("Downloading cover art from: {} for item: {}", imageUrl, item.getId());

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(imageUrl))
              .timeout(Duration.ofSeconds(15))
              .header("User-Agent", "Yay-Tsa-Media-Server/0.1.0")
              .GET()
              .build();

      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

      if (response.statusCode() != 200) {
        log.warn(
            "Failed to download cover art: HTTP {}, URL: {}", response.statusCode(), imageUrl);
        return false;
      }

      byte[] imageData = response.body();

      if (imageData == null || imageData.length == 0) {
        log.warn("Empty image data from URL: {}", imageUrl);
        return false;
      }

      return saveArtworkData(item, imageData, imageType, source);

    } catch (Exception e) {
      log.error("Failed to download cover art from: {}", imageUrl, e);
      return false;
    }
  }

  /**
   * Save artwork data to filesystem and database.
   *
   * @param item the item entity
   * @param imageData raw image bytes
   * @param imageType type of image
   * @param source source identifier
   * @return true if saved successfully
   */
  private boolean saveArtworkData(
      ItemEntity item, byte[] imageData, ImageType imageType, String source) {
    try {
      // Generate hash for filename
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(imageData);
      String hashHex = HexFormat.of().formatHex(hash).substring(0, 16);

      // Detect image format
      String extension = detectImageFormat(imageData);
      String filename = String.format("%s-%s-%s.%s", item.getId(), imageType, hashHex, extension);
      Path imagePath = imageCacheDirectory.resolve(filename);

      // Save to filesystem
      Files.write(imagePath, imageData);

      // Get image dimensions
      BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageData));
      int width = bufferedImage != null ? bufferedImage.getWidth() : 0;
      int height = bufferedImage != null ? bufferedImage.getHeight() : 0;

      // Check if image already exists for this item and type
      Optional<ImageEntity> existingImage =
          imageRepository.findFirstByItem_IdAndType(item.getId(), imageType);

      ImageEntity imageEntity;
      if (existingImage.isPresent()) {
        imageEntity = existingImage.get();
        // Update existing image
        imageEntity.setPath(imagePath.toAbsolutePath().toString());
        imageEntity.setWidth(width);
        imageEntity.setHeight(height);
        imageEntity.setSizeBytes((long) imageData.length);
        imageEntity.setTag(hashHex);
        log.debug("Updated existing {} image for item: {}", imageType, item.getId());
      } else {
        // Create new image entity
        imageEntity = new ImageEntity();
        imageEntity.setItem(item);
        imageEntity.setType(imageType);
        imageEntity.setPath(imagePath.toAbsolutePath().toString());
        imageEntity.setWidth(width);
        imageEntity.setHeight(height);
        imageEntity.setSizeBytes((long) imageData.length);
        imageEntity.setTag(hashHex);
        imageEntity.setIsPrimary(imageType == ImageType.Primary);
        log.debug("Created new {} image for item: {}", imageType, item.getId());
      }

      imageRepository.save(imageEntity);

      log.info(
          "Saved {} cover art for item: {} from source: {}, size: {} bytes, dimensions: {}x{}",
          imageType,
          item.getId(),
          source,
          imageData.length,
          width,
          height);

      return true;

    } catch (Exception e) {
      log.error("Failed to save artwork data for item: {}", item.getId(), e);
      return false;
    }
  }

  /**
   * Detect image format from byte data.
   *
   * @param imageData raw image bytes
   * @return file extension (jpg, png, etc.)
   */
  private boolean isAllowedImageUrl(String imageUrl) {
    try {
      URI uri = URI.create(imageUrl);
      String scheme = uri.getScheme();
      if (!"https".equalsIgnoreCase(scheme)) {
        return false;
      }
      String host = uri.getHost();
      if (host == null) {
        return false;
      }
      // Block private/internal IPs
      InetAddress addr = InetAddress.getByName(host);
      if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
        return false;
      }
      // Check allowlist
      return ALLOWED_IMAGE_HOSTS.stream().anyMatch(allowed ->
          host.equalsIgnoreCase(allowed) || host.endsWith("." + allowed));
    } catch (Exception e) {
      return false;
    }
  }

  private String detectImageFormat(byte[] imageData) throws IOException {
    try (InputStream is = new ByteArrayInputStream(imageData)) {
      BufferedImage image = ImageIO.read(is);
      if (image != null) {
        // Try to detect from magic bytes
        if (imageData.length > 3) {
          if (imageData[0] == (byte) 0xFF
              && imageData[1] == (byte) 0xD8
              && imageData[2] == (byte) 0xFF) {
            return "jpg";
          }
          if (imageData[0] == (byte) 0x89
              && imageData[1] == (byte) 0x50
              && imageData[2] == (byte) 0x4E
              && imageData[3] == (byte) 0x47) {
            return "png";
          }
        }
      }
      return "jpg"; // default
    }
  }
}
