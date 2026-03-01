package com.yaytsa.server.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class UuidUtils {

  private UuidUtils() {}

  public static UUID parseUuid(String value) {
    if (value == null) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public static List<UUID> parseUuidList(String value) {
    if (value == null || value.isBlank()) return Collections.emptyList();
    try {
      return Arrays.stream(value.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .map(UUID::fromString)
          .collect(Collectors.toList());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
