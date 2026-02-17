package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.TrackUploadService;
import com.yaytsa.server.dto.request.UploadUrlRequest;
import com.yaytsa.server.dto.response.BaseItemResponse;
import com.yaytsa.server.infrastructure.security.AuthenticatedUser;
import com.yaytsa.server.mapper.ItemMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controller for uploading audio tracks.
 *
 * <p>Allows users to upload their own tracks to the library with automatic metadata extraction,
 * duplicate detection, and proper artist/album linking.
 */
@RestController
@RequestMapping("/tracks")
@Tag(name = "Upload", description = "Track upload operations")
public class UploadController {

  private static final Logger log = LoggerFactory.getLogger(UploadController.class);

  private static final List<String> SUPPORTED_EXTENSIONS =
      List.of("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma");

  private final TrackUploadService uploadService;
  private final ItemMapper itemMapper;

  public UploadController(TrackUploadService uploadService, ItemMapper itemMapper) {
    this.uploadService = uploadService;
    this.itemMapper = itemMapper;
  }

  @Operation(
      summary = "Upload audio track",
      description =
          "Upload a music file to the library. Metadata is automatically extracted from the file tags. "
              + "Supports: mp3, flac, m4a, aac, ogg, opus, wav, wma. Max size: 100MB.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            description = "Track uploaded successfully",
            content = @Content(schema = @Schema(implementation = BaseItemResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file or unsupported format"),
        @ApiResponse(responseCode = "409", description = "Duplicate track already exists"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "413", description = "File too large (max 100MB)")
      })
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<?> uploadTrack(
      @Parameter(description = "Audio file to upload", required = true) @RequestParam("file")
          MultipartFile file,
      @Parameter(description = "Library root path (optional, uses default if not specified)")
          @RequestParam(required = false)
          String libraryRoot,
      @AuthenticationPrincipal AuthenticatedUser user) {

    log.info(
        "Upload request from user {} for file: {} ({})",
        user.getUsername(),
        file.getOriginalFilename(),
        formatFileSize(file.getSize()));

    // Validate file is not empty
    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body("File is empty");
    }

    // Validate file extension
    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || !hasValidExtension(originalFilename)) {
      return ResponseEntity.badRequest()
          .body(
              "Unsupported file format. Supported: "
                  + String.join(", ", SUPPORTED_EXTENSIONS));
    }

    try {
      var result = uploadService.uploadTrack(file, user.getUserEntity().getId(), libraryRoot);

      if (result.isDuplicate()) {
        log.warn("Duplicate track detected: {}", originalFilename);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                "Duplicate track: "
                    + result.existingTrack().getItem().getName()
                    + " by "
                    + result.artistName());
      }

      BaseItemResponse response = itemMapper.toDto(result.createdItem(), null);
      log.info(
          "Track uploaded successfully: {} by {} (ID: {})",
          result.createdItem().getName(),
          result.artistName(),
          result.createdItem().getId());

      return ResponseEntity.status(HttpStatus.CREATED).body(response);

    } catch (IllegalArgumentException e) {
      log.error("Invalid upload request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      log.error("Failed to upload track: {}", originalFilename, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to upload track: " + e.getMessage());
    }
  }

  private boolean hasValidExtension(String filename) {
    String extension = getFileExtension(filename).toLowerCase();
    return SUPPORTED_EXTENSIONS.contains(extension);
  }

  private String getFileExtension(String filename) {
    int lastDot = filename.lastIndexOf('.');
    return lastDot > 0 ? filename.substring(lastDot + 1) : "";
  }

  @Operation(
      summary = "Upload track from URL",
      description =
          "Download and upload a music track from a URL (YouTube, SoundCloud, etc.). "
              + "Metadata is automatically extracted from the downloaded file.")
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "201",
            description = "Track downloaded and uploaded successfully",
            content = @Content(schema = @Schema(implementation = BaseItemResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid URL or download failed"),
        @ApiResponse(responseCode = "409", description = "Duplicate track already exists"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
      })
  @PostMapping(value = "/upload-url", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> uploadTrackFromUrl(
      @RequestBody UploadUrlRequest request,
      @RequestParam(required = false) String libraryRoot,
      @AuthenticationPrincipal AuthenticatedUser user) {

    log.info("URL upload request from user {} for URL: {}", user.getUsername(), request.url());

    try {
      var result = uploadService.uploadTrackFromUrl(request.url(), user.getUserEntity().getId(), libraryRoot);

      if (result.isDuplicate()) {
        log.warn("Duplicate track detected from URL: {}", request.url());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                "Duplicate track: "
                    + result.existingTrack().getItem().getName()
                    + " by "
                    + result.artistName());
      }

      BaseItemResponse response = itemMapper.toDto(result.createdItem(), null);
      log.info(
          "Track from URL uploaded successfully: {} by {} (ID: {})",
          result.createdItem().getName(),
          result.artistName(),
          result.createdItem().getId());

      return ResponseEntity.status(HttpStatus.CREATED).body(response);

    } catch (IllegalArgumentException e) {
      log.error("Invalid URL upload request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      log.error("Failed to upload track from URL: {}", request.url(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to download and upload track: " + e.getMessage());
    }
  }

  private String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    String pre = "KMGTPE".charAt(exp - 1) + "";
    return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
  }
}
