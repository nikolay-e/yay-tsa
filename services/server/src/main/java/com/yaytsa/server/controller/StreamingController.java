package com.yaytsa.server.controller;

import com.yaytsa.server.domain.service.StreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Streaming", description = "Audio streaming and transcoding")
public class StreamingController {

  private final StreamingService streamingService;

  public StreamingController(StreamingService streamingService) {
    this.streamingService = streamingService;
  }

  @Operation(
      summary = "Stream audio file",
      description = "Stream audio with optional transcoding and byte-range support")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Full content"),
        @ApiResponse(responseCode = "206", description = "Partial content (range request)"),
        @ApiResponse(responseCode = "404", description = "Item not found"),
        @ApiResponse(responseCode = "416", description = "Requested range not satisfiable"),
        @ApiResponse(responseCode = "503", description = "Transcoding capacity exceeded")
      })
  @GetMapping("/Audio/{itemId}/stream")
  public void streamAudio(
      @Parameter(description = "Audio item ID") @PathVariable UUID itemId,
      @Parameter(description = "API key for authentication")
          @RequestParam(value = "api_key", required = false)
          String apiKey,
      @Parameter(description = "Device ID") @RequestParam(value = "deviceId", required = false)
          String deviceId,
      @Parameter(description = "Audio codec for transcoding")
          @RequestParam(value = "audioCodec", required = false)
          String audioCodec,
      @Parameter(description = "Container format")
          @RequestParam(value = "container", required = false)
          String container,
      @Parameter(description = "Direct stream without transcoding")
          @RequestParam(value = "static", defaultValue = "false")
          boolean staticStream,
      @Parameter(description = "Audio bitrate for transcoding")
          @RequestParam(value = "audioBitRate", required = false)
          String audioBitRate,
      @RequestHeader(value = "Range", required = false) String range,
      @RequestHeader(value = "If-Range", required = false) String ifRange,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
      HttpServletResponse response)
      throws IOException {

    streamingService.streamAudio(itemId, range, response);
  }

  @Operation(
      summary = "HEAD request for audio stream",
      description = "Get headers for audio stream without content")
  @RequestMapping(value = "/Audio/{itemId}/stream", method = RequestMethod.HEAD)
  public ResponseEntity<Void> streamAudioHead(
      @PathVariable UUID itemId,
      @RequestParam(value = "api_key", required = false) String apiKey,
      @RequestParam(value = "deviceId", required = false) String deviceId) {

    Resource resource = streamingService.getAudioResource(itemId);

    String mimeType = streamingService.getAudioMimeType(itemId);

    HttpHeaders headers = new HttpHeaders();
    headers.add("Accept-Ranges", "bytes");
    headers.setContentType(MediaType.parseMediaType(mimeType));

    try {
      long contentLength = resource.contentLength();
      headers.setContentLength(contentLength);
    } catch (IOException e) {
      headers.setContentLength(0);
    }

    return ResponseEntity.ok().headers(headers).build();
  }

  @Operation(
      summary = "Get audio stream URL",
      description = "Generate a URL for streaming an audio item")
  @GetMapping("/Audio/{itemId}/stream/url")
  public ResponseEntity<String> getStreamUrl(
      @PathVariable UUID itemId,
      @RequestParam(value = "api_key", required = false) String apiKey,
      @RequestParam(value = "deviceId", required = false) String deviceId,
      @RequestParam(value = "audioCodec", required = false) String audioCodec,
      @RequestParam(value = "container", required = false) String container,
      @RequestParam(value = "static", defaultValue = "false") boolean staticStream,
      @RequestParam(value = "audioBitRate", required = false) String audioBitRate) {

    String streamUrl = streamingService.getStreamUrl(itemId);

    return ResponseEntity.ok(streamUrl);
  }
}
