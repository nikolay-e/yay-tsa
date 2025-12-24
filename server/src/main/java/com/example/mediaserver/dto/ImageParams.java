package com.example.mediaserver.dto;

public record ImageParams(
    Integer maxWidth,
    Integer maxHeight,
    Integer quality,
    String format,
    String tag
) {
    public static final int DEFAULT_QUALITY = 85;
    public static final String DEFAULT_FORMAT = "webp";

    public ImageParams {
        if (quality == null) {
            quality = DEFAULT_QUALITY;
        }
        if (format == null || format.isBlank()) {
            format = DEFAULT_FORMAT;
        }
        if (quality < 1 || quality > 100) {
            throw new IllegalArgumentException("Quality must be between 1 and 100");
        }
    }

    public static ImageParams of(Integer maxWidth, Integer maxHeight, Integer quality, String format, String tag) {
        return new ImageParams(maxWidth, maxHeight, quality, format, tag);
    }

    public static ImageParams defaults() {
        return new ImageParams(null, null, DEFAULT_QUALITY, DEFAULT_FORMAT, null);
    }

    public boolean requiresResize() {
        return maxWidth != null || maxHeight != null;
    }

    public String cacheKey(String itemId, String imageType) {
        return String.format("%s-%s-%s-%s-%d-%s",
            itemId,
            imageType,
            maxWidth != null ? maxWidth : "orig",
            maxHeight != null ? maxHeight : "orig",
            quality,
            format
        );
    }
}
