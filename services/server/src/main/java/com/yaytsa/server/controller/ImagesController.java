package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ImageService;
import com.yaytsa.server.dto.ImageParams;
import com.yaytsa.server.infrastructure.persistence.entity.ImageEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ImageRepository;
import com.yaytsa.server.util.PathUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

@RestController
@Tag(name = "Images", description = "Image serving and processing")
public class ImagesController {

  private static final Logger log = LoggerFactory.getLogger(ImagesController.class);

  private final ImageService imageService;
  private final ImageRepository imageRepository;

  public ImagesController(ImageService imageService, ImageRepository imageRepository) {
    this.imageService = imageService;
    this.imageRepository = imageRepository;
  }

  @Operation(
      summary = "Get item image",
      description = "Retrieve an image for a media item with optional resizing")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Image retrieved successfully"),
        @ApiResponse(responseCode = "304", description = "Not modified (cached)"),
        @ApiResponse(responseCode = "404", description = "Image not found")
      })
  @GetMapping("/Items/{itemId}/Images/{imageType}")
  public ResponseEntity<byte[]> getItemImage(
      @Parameter(description = "Item ID") @PathVariable String itemId,
      @Parameter(description = "Image type") @PathVariable String imageType,
      @Parameter(description = "API key") @RequestParam(value = "api_key", required = false)
          String apiKey,
      @Parameter(description = "Cache tag for validation") @RequestParam(required = false)
          String tag,
      @Parameter(description = "Maximum width") @RequestParam(required = false) Integer maxWidth,
      @Parameter(description = "Maximum height") @RequestParam(required = false) Integer maxHeight,
      @Parameter(description = "Image quality (1-100)") @RequestParam(required = false)
          Integer quality,
      @Parameter(
              description =
                  "Output format (webp, jpeg, png). If not specified, serves original format via"
                      + " X-Accel-Redirect when no resize is needed.")
          @RequestParam(required = false)
          String format,
      WebRequest webRequest) {

    try {
      UUID itemUuid = UUID.fromString(itemId);

      Optional<String> etag = imageService.getImageTag(itemUuid, imageType);
      if (etag.isPresent() && webRequest.checkNotModified(etag.get())) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag.get()).build();
      }

      int clampedQuality = quality == null ? 85 : Math.min(100, Math.max(1, quality));
      ImageParams params = ImageParams.of(maxWidth, maxHeight, clampedQuality, format, tag);

      boolean requiresProcessing = params.requiresResize() || format != null;

      if (imageService.isXAccelRedirectEnabled() && !requiresProcessing) {
        Optional<Path> imagePath = imageService.getImagePath(itemUuid, imageType);
        if (imagePath.isPresent()) {
          String actualFormat = getFormatFromPath(imagePath.get());
          return handleXAccelRedirect(imagePath.get(), actualFormat, etag);
        }
      }

      Optional<byte[]> imageData = imageService.getItemImage(itemUuid, imageType, params);

      if (imageData.isEmpty()) {
        log.debug("Image not found: itemId={}, type={}", itemId, imageType);
        return ResponseEntity.notFound().build();
      }

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(getMediaTypeForFormat(params.format()));
      headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
      etag.ifPresent(headers::setETag);

      return ResponseEntity.ok().headers(headers).body(imageData.get());

    } catch (IllegalArgumentException e) {
      log.warn(
          "Invalid image request: itemId={}, type={}, error={}", itemId, imageType, e.getMessage());
      return ResponseEntity.badRequest().build();
    }
  }

  private ResponseEntity<byte[]> handleXAccelRedirect(
      Path imagePath, String format, Optional<String> etag) {
    String encodedPath = PathUtils.encodePathForHeader(imagePath.toAbsolutePath().toString());
    String redirectPath = imageService.getXAccelInternalPath() + encodedPath;

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(getMediaTypeForFormat(format));
    headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
    etag.ifPresent(headers::setETag);
    headers.add("X-Accel-Redirect", redirectPath);

    log.debug("X-Accel-Redirect image to: {}", redirectPath);
    return ResponseEntity.ok().headers(headers).build();
  }

  @Operation(
      summary = "Get image by index",
      description =
          "Get image by type and index for items with multiple images. "
              + "Note: Currently only returns the first image of the type (imageIndex is ignored).")
  @GetMapping("/Items/{itemId}/Images/{imageType}/{imageIndex}")
  public ResponseEntity<byte[]> getItemImageByIndex(
      @PathVariable String itemId,
      @PathVariable String imageType,
      @PathVariable int imageIndex,
      @RequestParam(value = "api_key", required = false) String apiKey,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) Integer maxWidth,
      @RequestParam(required = false) Integer maxHeight,
      @RequestParam(required = false) Integer quality,
      @RequestParam(required = false) String format,
      WebRequest webRequest) {
    // TODO: Implement imageIndex support in ImageService.findByItemIdAndTypeWithIndex()
    // Currently only one image per type is supported, so imageIndex is ignored
    return getItemImage(
        itemId, imageType, apiKey, tag, maxWidth, maxHeight, quality, format, webRequest);
  }

  @Operation(summary = "Upload image for item", description = "Upload a new image for a media item")
  @ApiResponse(responseCode = "501", description = "Not implemented")
  @PostMapping("/Items/{itemId}/Images/{imageType}")
  public ResponseEntity<Void> uploadItemImage(
      @PathVariable String itemId,
      @PathVariable String imageType,
      @RequestBody byte[] imageData,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(summary = "Delete item image", description = "Delete an image for a media item")
  @ApiResponse(responseCode = "501", description = "Not implemented")
  @DeleteMapping("/Items/{itemId}/Images/{imageType}")
  public ResponseEntity<Void> deleteItemImage(
      @PathVariable String itemId,
      @PathVariable String imageType,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @Operation(
      summary = "Get available image types",
      description = "Get list of available image types for an item")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Image types retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid item ID")
      })
  @GetMapping("/Items/{itemId}/Images")
  public ResponseEntity<List<ImageTypeInfo>> getItemImageTypes(
      @PathVariable String itemId,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    try {
      UUID itemUuid = UUID.fromString(itemId);
      List<ImageEntity> images = imageRepository.findAllByItem_Id(itemUuid);

      List<ImageTypeInfo> imageTypes =
          images.stream()
              .map(
                  img ->
                      new ImageTypeInfo(
                          img.getType().name(), img.getWidth(), img.getHeight(), img.getTag()))
              .toList();

      return ResponseEntity.ok(imageTypes);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid item ID: {}", itemId);
      return ResponseEntity.badRequest().build();
    }
  }

  public record ImageTypeInfo(String imageType, Integer width, Integer height, String tag) {}

  private MediaType getMediaTypeForFormat(String format) {
    if (format == null) {
      return MediaType.IMAGE_JPEG;
    }
    return switch (format.toLowerCase(java.util.Locale.ROOT)) {
      case "webp" -> MediaType.parseMediaType("image/webp");
      case "png" -> MediaType.IMAGE_PNG;
      case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
      default -> MediaType.IMAGE_JPEG;
    };
  }

  private String getFormatFromPath(Path path) {
    String fileName = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (fileName.endsWith(".webp")) {
      return "webp";
    }
    if (fileName.endsWith(".png")) {
      return "png";
    }
    if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
      return "jpeg";
    }
    return "jpeg";
  }
}
