package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.ImageService;
import com.yaytsa.server.dto.ImageParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

@RestController
@Tag(name = "Images", description = "Image serving and processing")
public class ImagesController {

  private static final Logger log = LoggerFactory.getLogger(ImagesController.class);

  private final ImageService imageService;

  public ImagesController(ImageService imageService) {
    this.imageService = imageService;
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
      @Parameter(description = "Output format (webp, jpeg, png)")
          @RequestParam(defaultValue = "webp")
          String format,
      WebRequest webRequest) {

    try {
      UUID itemUuid = UUID.fromString(itemId);

      Optional<String> etag = imageService.getImageTag(itemUuid, imageType);
      if (etag.isPresent() && webRequest.checkNotModified(etag.get())) {
        return null;
      }

      int clampedQuality = quality == null ? 85 : Math.min(100, Math.max(1, quality));
      ImageParams params = ImageParams.of(maxWidth, maxHeight, clampedQuality, format, tag);
      Optional<byte[]> imageData = imageService.getItemImage(itemUuid, imageType, params);

      if (imageData.isEmpty()) {
        log.debug("Image not found: itemId={}, type={}", itemId, imageType);
        return ResponseEntity.notFound().build();
      }

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(getMediaTypeForFormat(format));
      headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
      etag.ifPresent(headers::setETag);

      return ResponseEntity.ok().headers(headers).body(imageData.get());

    } catch (IllegalArgumentException e) {
      log.warn(
          "Invalid image request: itemId={}, type={}, error={}", itemId, imageType, e.getMessage());
      return ResponseEntity.badRequest().build();
    }
  }

  @Operation(
      summary = "Get image by index",
      description = "Get image by type and index for items with multiple images")
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
      @RequestParam(defaultValue = "webp") String format,
      WebRequest webRequest) {

    return getItemImage(
        itemId, imageType, apiKey, tag, maxWidth, maxHeight, quality, format, webRequest);
  }

  @Operation(summary = "Upload image for item", description = "Upload a new image for a media item")
  @PostMapping("/Items/{itemId}/Images/{imageType}")
  public ResponseEntity<Void> uploadItemImage(
      @PathVariable String itemId,
      @PathVariable String imageType,
      @RequestBody byte[] imageData,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    // TODO: Implement image upload
    return ResponseEntity.noContent().build();
  }

  @Operation(summary = "Delete item image", description = "Delete an image for a media item")
  @DeleteMapping("/Items/{itemId}/Images/{imageType}")
  public ResponseEntity<Void> deleteItemImage(
      @PathVariable String itemId,
      @PathVariable String imageType,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    // TODO: Implement image deletion
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Get available image types",
      description = "Get list of available image types for an item")
  @GetMapping("/Items/{itemId}/Images")
  public ResponseEntity<Object[]> getItemImageTypes(
      @PathVariable String itemId,
      @RequestParam(value = "api_key", required = false) String apiKey) {

    // TODO: Implement listing of available image types
    Object[] imageTypes = new Object[0];
    return ResponseEntity.ok(imageTypes);
  }

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
}
