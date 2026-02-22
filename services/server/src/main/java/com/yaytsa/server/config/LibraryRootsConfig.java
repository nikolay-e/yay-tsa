package com.yaytsa.server.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parses the comma-separated {@code yaytsa.media.library.roots} property into a list of absolute
 * paths and exposes a single {@link #isPathSafe(Path)} that returns {@code true} when the given
 * path lives under any of those roots.
 *
 * <p>Using a single config bean avoids the bug where services inject the comma-separated string
 * directly into {@code Paths.get()}, which treats the whole string (including commas) as a single
 * invalid path, causing {@code toRealPath()} to return {@code null} and every path check to fail.
 */
@Component
public class LibraryRootsConfig {

  private static final Logger log = LoggerFactory.getLogger(LibraryRootsConfig.class);

  private final List<Path> roots;

  public LibraryRootsConfig(
      @Value("${yaytsa.media.library.roots:/media}") String libraryRoots) {
    this.roots = parseRoots(libraryRoots);
    log.info("Library roots configured: {}", this.roots);
  }

  private List<Path> parseRoots(String libraryRoots) {
    return Arrays.stream(libraryRoots.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> Paths.get(s).toAbsolutePath().normalize())
        .collect(Collectors.toList());
  }

  /**
   * Returns {@code true} if the given path resolves to a real path that starts with any configured
   * library root. Returns {@code false} if the file does not exist or if an I/O error occurs.
   */
  public boolean isPathSafe(Path path) {
    try {
      Path realPath = path.toRealPath();
      for (Path root : roots) {
        Path realRoot;
        try {
          realRoot = root.toRealPath();
        } catch (IOException e) {
          realRoot = root; // root not yet mounted — compare against normalized path
        }
        if (realPath.startsWith(realRoot)) {
          return true;
        }
      }
      return false;
    } catch (java.nio.file.NoSuchFileException e) {
      log.debug("Path does not exist: {}", path);
      return false;
    } catch (IOException e) {
      log.warn("Path validation failed for {}: {}", path, e.getMessage());
      return false;
    }
  }

  public List<Path> getRoots() {
    return Collections.unmodifiableList(roots);
  }
}
