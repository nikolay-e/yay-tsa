package com.yaytsa.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yaytsa.server.domain.service.TrackUploadService;
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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/tracks")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Upload", description = "Track upload operations (admin only)")
public class UploadController {

  private static final Logger log = LoggerFactory.getLogger(UploadController.class);

  private static final List<String> SUPPORTED_EXTENSIONS =
      List.of("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma");

  private static final Set<String> ALLOWED_MIME_TYPES =
      Set.of(
          "audio/mpeg", "audio/flac", "audio/x-flac", "audio/mp4",
          "audio/wav", "audio/x-wav", "audio/ogg", "audio/aac",
          "audio/x-m4a", "audio/x-ms-wma", "audio/opus");

  private final TrackUploadService uploadService;
  private final ItemMapper itemMapper;
  private final ObjectMapper objectMapper;

  public UploadController(
      TrackUploadService uploadService, ItemMapper itemMapper, ObjectMapper objectMapper) {
    this.uploadService = uploadService;
    this.itemMapper = itemMapper;
    this.objectMapper = objectMapper;
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

    // Validate MIME type
    String contentType = file.getContentType();
    if (contentType != null && !ALLOWED_MIME_TYPES.contains(contentType)) {
      return ResponseEntity.badRequest()
          .body("Unsupported content type: " + contentType);
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
          "Track uploaded successfully: {} by {} (ID: {}), albumComplete={}",
          result.createdItem().getName(),
          result.artistName(),
          result.createdItem().getId(),
          result.albumComplete());

      // Inject IsComplete from album completion status into the track response so the
      // frontend can show ⏳ for incomplete albums without a separate album request.
      ObjectNode responseNode = objectMapper.valueToTree(response);
      responseNode.put("IsComplete", result.albumComplete());

      return ResponseEntity.status(HttpStatus.CREATED).body(responseNode);

    } catch (IllegalArgumentException e) {
      log.error("Invalid upload request: {}", e.getMessage());
      return ResponseEntity.badRequest().body(e.getMessage());
    } catch (Exception e) {
      log.error("Failed to upload track: {}", originalFilename, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Failed to upload track. Please try again.");
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

  private String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    String pre = "KMGTPE".charAt(exp - 1) + "";
    return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
  }
}
