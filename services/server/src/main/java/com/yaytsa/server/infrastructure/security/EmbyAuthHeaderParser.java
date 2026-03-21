package com.yaytsa.server.infrastructure.security;

import java.util.HashMap;
import java.util.Map;

public final class EmbyAuthHeaderParser {

  private EmbyAuthHeaderParser() {}

  public static Map<String, String> parse(String header) {
    Map<String, String> result = new HashMap<>();

    if (header == null || header.isEmpty()) {
      return result;
    }

    String content = header;
    if (content.startsWith("MediaBrowser ")) {
      content = content.substring("MediaBrowser ".length());
    }

    String[] pairs = content.split(",");
    for (String pair : pairs) {
      String trimmed = pair.trim();
      int equalPos = trimmed.indexOf('=');
      if (equalPos > 0) {
        String key = trimmed.substring(0, equalPos).trim();
        String value = trimmed.substring(equalPos + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        result.put(key, value);
      }
    }

    return result;
  }

  public static EmbyAuthCredentials parseToCredentials(String header) {
    Map<String, String> parts = parse(header);
    return new EmbyAuthCredentials(
        parts.get("Token"),
        parts.getOrDefault("DeviceId", "unknown"),
        parts.get("Device"),
        parts.get("Client"),
        parts.get("Version"));
  }
}
