package com.yaytsa.server.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class PathUtils {

  private PathUtils() {}

  public static String encodePathForHeader(String path) {
    StringBuilder encoded = new StringBuilder();
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        String encodedSegment =
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
        encoded.append("/").append(encodedSegment);
      }
    }
    return encoded.toString();
  }
}
