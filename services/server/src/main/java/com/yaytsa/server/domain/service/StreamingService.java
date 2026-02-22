package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.ItemEntity;
import com.yaytsa.server.infrastructure.persistence.repository.ItemRepository;
import com.yaytsa.server.util.PathUtils;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StreamingService {

  private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d+)-(\\d*)$");
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(StreamingService.class);

  private final ItemRepository itemRepository;
  private final String baseUrl;
  private final Path mediaRootPath;
  private final boolean xAccelRedirectEnabled;
  private final String xAccelInternalPath;
  private volatile Path realMediaRoot;
  private final byte[] etagSecret;

  public StreamingService(
      ItemRepository itemRepository,
      @Value("${server.base-url:http://localhost:8080}") String baseUrl,
      @Value("${yaytsa.media.library.roots:/media}") String mediaRoot,
      @Value("${yaytsa.media.streaming.x-accel-redirect.enabled:false}")
          boolean xAccelRedirectEnabled,
      @Value("${yaytsa.media.streaming.x-accel-redirect.internal-path:/_internal/media}")
          String xAccelInternalPath) {
    this.itemRepository = itemRepository;
    this.baseUrl = baseUrl;
    this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
    this.xAccelRedirectEnabled = xAccelRedirectEnabled;
    this.xAccelInternalPath = xAccelInternalPath;
    if (xAccelRedirectEnabled) {
      log.info("X-Accel-Redirect enabled with internal path: {}", xAccelInternalPath);
    }
    this.etagSecret = java.security.SecureRandom.getSeed(32);
    initRealMediaRoot();
  }

  private void initRealMediaRoot() {
    try {
      this.realMediaRoot = mediaRootPath.toRealPath();
      log.info("Initialized media root path: {}", realMediaRoot);
    } catch (IOException e) {
      log.warn("Media root path not accessible at startup: {}", mediaRootPath);
      this.realMediaRoot = null;
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
      log.warn("Failed to resolve media root path: {}", e.getMessage());
      return null;
    }
  }

  public String getStreamUrl(UUID itemId) {
    return String.format("%s/Audio/%s/stream", baseUrl, itemId);
  }

  private record ResolvedFile(Path filePath, String container) {}

  @Transactional(readOnly = true)
  public void streamAudio(UUID itemId, String rangeHeader, HttpServletResponse response)
      throws IOException {

    ResolvedFile resolved = resolveFile(itemId);

    long fileSize = Files.size(resolved.filePath());
    String mimeType = detectMimeType(resolved.filePath(), resolved.container());
    String etag = generateETag(resolved.filePath(), fileSize);

    response.setHeader("Accept-Ranges", "bytes");
    response.setHeader("ETag", etag);

    if (xAccelRedirectEnabled) {
      handleXAccelRedirect(resolved.filePath(), fileSize, mimeType, response);
    } else if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
      handleRangeRequest(resolved.filePath(), fileSize, mimeType, rangeHeader, response);
    } else {
      handleFullRequest(resolved.filePath(), fileSize, mimeType, response);
    }
  }

  private ResolvedFile resolveFile(UUID itemId) {
    ItemEntity item =
        itemRepository
            .findById(itemId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found: " + itemId));

    if (item.getPath() == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "File path not available for item: " + itemId);
    }

    Path filePath = Paths.get(item.getPath()).toAbsolutePath().normalize();

    if (!isPathSafe(filePath)) {
      log.error("Path traversal attempt detected for item {}: {}", itemId, filePath);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    try {
      BasicFileAttributes attrs =
          Files.readAttributes(filePath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isRegularFile()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
      }
    } catch (java.nio.file.NoSuchFileException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + item.getPath());
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + item.getPath());
    }

    return new ResolvedFile(filePath, item.getContainer());
  }

  private void handleXAccelRedirect(
      Path filePath, long fileSize, String mimeType, HttpServletResponse response) {

    String encodedPath = PathUtils.encodePathForHeader(filePath.toAbsolutePath().toString());
    String redirectPath = xAccelInternalPath + encodedPath;

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(mimeType);
    response.setContentLengthLong(fileSize);
    response.setHeader("X-Accel-Redirect", redirectPath);
    response.setHeader("X-Accel-Buffering", "no");

    log.debug("X-Accel-Redirect to: {}", redirectPath);
  }

  @Transactional(readOnly = true)
  public Resource getAudioResource(UUID itemId) {
    ResolvedFile resolved = resolveFile(itemId);
    return new FileSystemResource(resolved.filePath());
  }

  @Transactional(readOnly = true)
  public String getAudioMimeType(UUID itemId) {
    ResolvedFile resolved = resolveFile(itemId);
    return detectMimeType(resolved.filePath(), resolved.container());
  }

  private void handleFullRequest(
      Path filePath, long fileSize, String mimeType, HttpServletResponse response)
      throws IOException {

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(mimeType);
    response.setContentLengthLong(fileSize);

    try (FileChannel fileChannel =
            FileChannel.open(filePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        OutputStream outputStream = response.getOutputStream()) {

      transferFully(fileChannel, 0, fileSize, Channels.newChannel(outputStream));
    }
  }

  private void handleRangeRequest(
      Path filePath,
      long fileSize,
      String mimeType,
      String rangeHeader,
      HttpServletResponse response)
      throws IOException {

    Matcher matcher = RANGE_PATTERN.matcher(rangeHeader);
    if (!matcher.matches()) {
      response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
      response.setHeader("Content-Range", "bytes */" + fileSize);
      return;
    }

    long start = Long.parseLong(matcher.group(1));
    long end = matcher.group(2).isEmpty() ? fileSize - 1 : Long.parseLong(matcher.group(2));

    if (start >= fileSize || end >= fileSize || start > end) {
      response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
      response.setHeader("Content-Range", "bytes */" + fileSize);
      return;
    }

    long contentLength = end - start + 1;

    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
    response.setContentType(mimeType);
    response.setContentLengthLong(contentLength);
    response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileSize));

    try (FileChannel fileChannel =
            FileChannel.open(filePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        OutputStream outputStream = response.getOutputStream()) {

      transferFully(fileChannel, start, contentLength, Channels.newChannel(outputStream));
    }
  }

  private void transferFully(
      FileChannel fileChannel,
      long position,
      long count,
      java.nio.channels.WritableByteChannel target)
      throws IOException {
    long remaining = count;
    while (remaining > 0) {
      long transferred = fileChannel.transferTo(position, remaining, target);
      if (transferred <= 0) {
        break;
      }
      position += transferred;
      remaining -= transferred;
    }
  }

  private String detectMimeType(Path filePath, String container) {
    if (container != null) {
      String mimeFromContainer = mimeTypeFromContainer(container);
      if (mimeFromContainer != null) {
        return mimeFromContainer;
      }
    }

    String fileName = filePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (fileName.endsWith(".mp3")) {
      return "audio/mpeg";
    } else if (fileName.endsWith(".m4a") || fileName.endsWith(".aac")) {
      return "audio/mp4";
    } else if (fileName.endsWith(".flac")) {
      return "audio/flac";
    } else if (fileName.endsWith(".opus")) {
      return "audio/opus";
    } else if (fileName.endsWith(".ogg")) {
      return "audio/ogg";
    } else if (fileName.endsWith(".wav")) {
      return "audio/wav";
    } else if (fileName.endsWith(".wma")) {
      return "audio/x-ms-wma";
    }

    try {
      String probedType = Files.probeContentType(filePath);
      if (probedType != null && probedType.startsWith("audio/")) {
        return probedType;
      }
    } catch (IOException ignored) {
    }

    return "audio/mpeg";
  }

  private String mimeTypeFromContainer(String container) {
    if (container == null) {
      return null;
    }
    for (String format : container.split(",")) {
      String mime = getSingleContainerMimeType(format.trim().toLowerCase(java.util.Locale.ROOT));
      if (mime != null) {
        return mime;
      }
    }
    return null;
  }

  private String getSingleContainerMimeType(String container) {
    return switch (container) {
      case "mp3" -> "audio/mpeg";
      case "m4a", "aac" -> "audio/mp4";
      case "flac" -> "audio/flac";
      case "opus" -> "audio/opus";
      case "ogg" -> "audio/ogg";
      case "wav" -> "audio/wav";
      case "wma" -> "audio/x-ms-wma";
      default -> null;
    };
  }

  private boolean isPathSafe(Path filePath) {
    try {
      Path root = getRealMediaRoot();
      if (root == null) {
        return false;
      }
      Path realPath = filePath.toRealPath();
      return realPath.startsWith(root);
    } catch (java.nio.file.NoSuchFileException e) {
      log.debug("Path does not exist: {}", filePath);
      return false;
    } catch (IOException e) {
      log.warn("Path validation failed for {}: {}", filePath, e.getMessage());
      return false;
    }
  }

  private String generateETag(Path filePath, long fileSize) {
    try {
      long lastModified = Files.getLastModifiedTime(filePath).toMillis();
      String data = lastModified + "-" + fileSize + "-" + filePath.toAbsolutePath();
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(etagSecret, "HmacSHA256"));
      byte[] hash = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String shortHash = java.util.HexFormat.of().formatHex(hash, 0, 16);
      return "\"" + shortHash + "\"";
    } catch (Exception e) {
      return "\"" + Long.toHexString(fileSize) + "\"";
    }
  }
}
