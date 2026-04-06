package com.yaytsa.server.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LibraryRootsConfig {

  private final List<Path> roots;

  public LibraryRootsConfig(@Value("${yaytsa.media.library.roots:/media}") String libraryRoots) {
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
