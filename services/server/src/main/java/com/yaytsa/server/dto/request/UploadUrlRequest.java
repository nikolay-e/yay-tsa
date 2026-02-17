package com.yaytsa.server.dto.request;

public record UploadUrlRequest(String url) {
  public UploadUrlRequest {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("URL cannot be empty");
    }
  }
}
