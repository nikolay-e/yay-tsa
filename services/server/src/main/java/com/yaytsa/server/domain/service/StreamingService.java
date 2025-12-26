package com.yaytsa.server.domain.service;

import com.yaytsa.server.infra.persistence.entity.ItemEntity;
import com.yaytsa.server.infra.persistence.repository.ItemRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class StreamingService {

    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d+)-(\\d*)$");
    private static final int BUFFER_SIZE = 8192;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StreamingService.class);

    private final ItemRepository itemRepository;
    private final String baseUrl;
    private final Path mediaRootPath;

    public StreamingService(
            ItemRepository itemRepository,
            @Value("${server.base-url:http://localhost:8080}") String baseUrl,
            @Value("${yaytsa.media.library.roots:/media}") String mediaRoot) {
        this.itemRepository = itemRepository;
        this.baseUrl = baseUrl;
        this.mediaRootPath = Paths.get(mediaRoot).toAbsolutePath().normalize();
    }

    public String getStreamUrl(UUID itemId) {
        return String.format("%s/Audio/%s/stream", baseUrl, itemId);
    }

    public void streamAudio(
            UUID itemId,
            String rangeHeader,
            HttpServletResponse response) throws IOException {

        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Item not found: " + itemId));

        if (item.getPath() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "File path not available for item: " + itemId);
        }

        Path filePath = Paths.get(item.getPath()).toAbsolutePath().normalize();

        if (!filePath.startsWith(mediaRootPath)) {
            log.error("Path traversal attempt detected for item {}: {}", itemId, filePath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "File not found: " + item.getPath());
        }

        long fileSize = Files.size(filePath);
        String mimeType = detectMimeType(filePath, item.getContainer());
        String etag = generateETag(filePath, fileSize);

        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("ETag", etag);

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            handleRangeRequest(filePath, fileSize, mimeType, rangeHeader, response);
        } else {
            handleFullRequest(filePath, fileSize, mimeType, response);
        }
    }

    public Resource getAudioResource(UUID itemId) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Item not found: " + itemId));

        if (item.getPath() == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "File path not available for item: " + itemId);
        }

        Path filePath = Paths.get(item.getPath()).toAbsolutePath().normalize();

        if (!filePath.startsWith(mediaRootPath)) {
            log.error("Path traversal attempt detected for item {}: {}", itemId, filePath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (!Files.exists(filePath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "File not found: " + item.getPath());
        }

        return new FileSystemResource(filePath);
    }

    public String getAudioMimeType(UUID itemId) {
        ItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Item not found: " + itemId));

        if (item.getPath() == null) {
            return "audio/mpeg";
        }

        Path filePath = Paths.get(item.getPath()).toAbsolutePath().normalize();

        if (!filePath.startsWith(mediaRootPath)) {
            log.error("Path traversal attempt detected for item {}: {}", itemId, filePath);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return detectMimeType(filePath, item.getContainer());
    }

    private void handleFullRequest(
            Path filePath,
            long fileSize,
            String mimeType,
            HttpServletResponse response) throws IOException {

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(mimeType);
        response.setContentLengthLong(fileSize);

        try (FileChannel fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
             OutputStream outputStream = response.getOutputStream()) {

            fileChannel.transferTo(0, fileSize, Channels.newChannel(outputStream));
        }
    }

    private void handleRangeRequest(
            Path filePath,
            long fileSize,
            String mimeType,
            String rangeHeader,
            HttpServletResponse response) throws IOException {

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

        try (FileChannel fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
             OutputStream outputStream = response.getOutputStream()) {

            fileChannel.position(start);
            fileChannel.transferTo(start, contentLength, Channels.newChannel(outputStream));
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
        return switch (container.toLowerCase(java.util.Locale.ROOT)) {
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

    private String generateETag(Path filePath, long fileSize) {
        try {
            String etag = String.format("%d-%d-%d",
                    Files.getLastModifiedTime(filePath).toMillis(),
                    fileSize,
                    filePath.toAbsolutePath().hashCode());
            return "\"" + DigestUtils.md5Hex(etag) + "\"";
        } catch (IOException e) {
            return "\"" + DigestUtils.md5Hex(filePath.toString() + fileSize) + "\"";
        }
    }
}
