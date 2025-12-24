package com.example.mediaserver.domain.service;

import com.example.mediaserver.dto.ImageParams;
import com.example.mediaserver.infra.persistence.entity.ImageEntity;
import com.example.mediaserver.infra.persistence.entity.ImageType;
import com.example.mediaserver.infra.persistence.entity.ItemEntity;
import com.example.mediaserver.infra.persistence.repository.ImageRepository;
import com.example.mediaserver.infra.persistence.repository.ItemRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.coobird.thumbnailator.Thumbnails;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
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

@Service
public class ImageService {
    private static final Logger logger = LoggerFactory.getLogger(ImageService.class);

    private final ImageRepository imageRepository;
    private final ItemRepository itemRepository;
    private final Cache<String, byte[]> imageCache;

    public ImageService(
        ImageRepository imageRepository,
        ItemRepository itemRepository
    ) {
        this.imageRepository = imageRepository;
        this.itemRepository = itemRepository;
        this.imageCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(Duration.ofHours(1))
            .recordStats()
            .build();
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
            logger.warn("Invalid image type: {}", imageTypeStr);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error processing image for itemId={}, type={}", itemId, imageTypeStr, e);
            return Optional.empty();
        }
    }

    public Optional<String> getImageTag(UUID itemId, String imageTypeStr) {
        try {
            ImageType imageType = ImageType.valueOf(imageTypeStr);
            return imageRepository.findByItemIdAndType(itemId, imageType)
                .map(this::generateETag);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid image type: {}", imageTypeStr);
            return Optional.empty();
        }
    }

    public Optional<byte[]> extractAlbumArt(Path audioFilePath) {
        if (!Files.exists(audioFilePath)) {
            logger.warn("Audio file not found: {}", audioFilePath);
            return Optional.empty();
        }

        try {
            AudioFile audioFile = AudioFileIO.read(audioFilePath.toFile());
            Tag tag = audioFile.getTag();

            if (tag != null && tag.getFirstArtwork() != null) {
                Artwork artwork = tag.getFirstArtwork();
                byte[] imageData = artwork.getBinaryData();
                logger.debug("Extracted album art from: {}, size: {} bytes", audioFilePath, imageData.length);
                return Optional.of(imageData);
            }

            logger.debug("No embedded artwork found in: {}", audioFilePath);
            return Optional.empty();

        } catch (Exception e) {
            logger.error("Failed to extract album art from: {}", audioFilePath, e);
            return Optional.empty();
        }
    }

    private Optional<byte[]> loadImageData(UUID itemId, ImageType imageType) throws IOException {
        Optional<ImageEntity> imageEntity = imageRepository.findByItemIdAndType(itemId, imageType);

        if (imageEntity.isPresent()) {
            Path imagePath = Paths.get(imageEntity.get().getPath());
            if (Files.exists(imagePath)) {
                return Optional.of(Files.readAllBytes(imagePath));
            }
            logger.warn("Image file not found on disk: {}", imagePath);
        }

        if (imageType == ImageType.Primary) {
            Optional<ItemEntity> item = itemRepository.findById(itemId);
            if (item.isPresent() && item.get().getPath() != null) {
                Optional<byte[]> embeddedArt = extractAlbumArt(Paths.get(item.get().getPath()));
                if (embeddedArt.isPresent()) {
                    return embeddedArt;
                }
            }
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
            if (!ImageIO.write(image, "webp", outputStream)) {
                logger.warn("WebP encoding failed, falling back to JPEG");
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
        return switch (format.toLowerCase()) {
            case "jpg", "jpeg" -> "jpeg";
            case "webp" -> "webp";
            case "png" -> "png";
            default -> "jpeg";
        };
    }

    private String generateETag(ImageEntity image) {
        try {
            String data = image.getId() + "-" +
                         image.getPath() + "-" +
                         (image.getSizeBytes() != null ? image.getSizeBytes() : "0") + "-" +
                         (image.getTag() != null ? image.getTag() : "");

            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data.getBytes());
            return "\"" + HexFormat.of().formatHex(hash) + "\"";
        } catch (NoSuchAlgorithmException e) {
            return "\"" + image.getId().toString() + "\"";
        }
    }

    public Cache<String, byte[]> getCache() {
        return imageCache;
    }
}
