package com.yaytsa.server.infrastructure.fs;

import java.nio.file.Path;

public final class PathUtils {

  private PathUtils() {}

  public static String getFilenameWithoutExtension(Path filePath) {
    return filePath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
  }
}
