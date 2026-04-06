package com.yaytsa.server.domain.util;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class EmbeddingUtils {

  private static final float NORMALIZATION_THRESHOLD = 1e-9f;

  private EmbeddingUtils() {}

  public static String format(float[] embedding) {
    return IntStream.range(0, embedding.length)
        .mapToObj(i -> String.valueOf(embedding[i]))
        .collect(Collectors.joining(",", "[", "]"));
  }

  public static String format(List<Float> embedding) {
    return IntStream.range(0, embedding.size())
        .mapToObj(i -> String.valueOf(embedding.get(i)))
        .collect(Collectors.joining(",", "[", "]"));
  }

  public static float[] parse(Object raw) {
    if (raw instanceof float[] arr) return arr;
    if (raw == null) return null;
    try {
      String str = raw.toString();
      if (str.startsWith("[")) str = str.substring(1);
      if (str.endsWith("]")) str = str.substring(0, str.length() - 1);
      String[] parts = str.split(",");
      float[] result = new float[parts.length];
      for (int i = 0; i < parts.length; i++) {
        result[i] = Float.parseFloat(parts[i].trim());
      }
      return result;
    } catch (NumberFormatException e) {
      log.warn("Malformed embedding data: {}", e.getMessage());
      return null;
    }
  }

  public static float[] l2Normalize(float[] vec) {
    float norm = 0;
    for (float v : vec) norm += v * v;
    norm = (float) Math.sqrt(norm);
    if (norm < NORMALIZATION_THRESHOLD) return vec.clone();
    float[] result = new float[vec.length];
    for (int i = 0; i < vec.length; i++) result[i] = vec[i] / norm;
    return result;
  }

  public static void l2NormalizeInPlace(float[] vec) {
    double norm = 0;
    for (float v : vec) norm += v * v;
    norm = Math.sqrt(norm);
    if (norm > NORMALIZATION_THRESHOLD) {
      for (int i = 0; i < vec.length; i++) vec[i] /= (float) norm;
    }
  }
}
