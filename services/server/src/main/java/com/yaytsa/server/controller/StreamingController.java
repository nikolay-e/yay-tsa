package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.StreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Streaming", description = "Audio streaming endpoints")
public class StreamingController {

  private final StreamingService streamingService;

  public StreamingController(StreamingService streamingService) {
    this.streamingService = streamingService;
  }

  @Operation(summary = "Stream audio file", description = "Stream audio with byte-range support")
  @SecurityRequirement(name = "apiKey")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Audio stream"),
        @ApiResponse(responseCode = "206", description = "Partial content"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Track not found")
      })
  @GetMapping("/Audio/{itemId}/stream")
  public void streamAudio(
      @PathVariable UUID itemId,
      @RequestHeader(value = "Range", required = false) String range,
      HttpServletResponse response)
      throws IOException {

    streamingService.streamAudio(itemId, range, response);
  }

  @Operation(summary = "Get audio stream headers")
  @SecurityRequirement(name = "apiKey")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Headers returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Track not found")
      })
  @RequestMapping(value = "/Audio/{itemId}/stream", method = RequestMethod.HEAD)
  public ResponseEntity<Void> streamAudioHead(@PathVariable UUID itemId) {

    Resource resource = streamingService.getAudioResource(itemId);
    String mimeType = streamingService.getAudioMimeType(itemId);

    HttpHeaders headers = new HttpHeaders();
    headers.add("Accept-Ranges", "bytes");
    headers.setContentType(MediaType.parseMediaType(mimeType));

    try {
      headers.setContentLength(resource.contentLength());
    } catch (IOException e) {
      headers.setContentLength(0);
    }

    return ResponseEntity.ok().headers(headers).build();
  }

  @Operation(summary = "Get stream URL for audio")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Stream URL returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Track not found")
      })
  @GetMapping("/Audio/{itemId}/stream/url")
  public ResponseEntity<Map<String, String>> getStreamUrl(@PathVariable UUID itemId) {

    String streamUrl = streamingService.getStreamUrl(itemId);
    return ResponseEntity.ok(Map.of("url", streamUrl));
  }
}
