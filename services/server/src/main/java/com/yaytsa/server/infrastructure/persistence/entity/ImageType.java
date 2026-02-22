package com.yaytsa.server.infrastructure.persistence.entity;

import java.util.Optional;

public enum ImageType {
  Primary,
  Art,
  Backdrop,
  Banner,
  Logo,
  Thumb,
  Disc,
  Box,
  Screenshot,
  Menu,
  Chapter;

  public static Optional<ImageType> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (ImageType type : values()) {
      if (type.name().equalsIgnoreCase(value)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }
}
