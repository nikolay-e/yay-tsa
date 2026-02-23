package com.yaytsa.server.domain.service.radio;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MockLlmProvider implements LlmAnalysisProvider {

  private static final String[] MOODS = {
    "happy", "sad", "calm", "energetic", "aggressive", "romantic", "melancholic", "nostalgic"
  };

  @Override
  public Optional<TrackAnalysis> analyzeTrack(TrackContext context) {
    int hash = Math.abs((context.artist() + context.title()).hashCode());

    String mood = MOODS[hash % MOODS.length];
    int energy = (hash % 10) + 1;
    int valence = ((hash / 10) % 10) + 1;
    int danceability = ((hash / 100) % 10) + 1;

    String language = detectLanguage(context);
    String themes = deriveThemes(context, mood);

    return Optional.of(
        new TrackAnalysis(mood, energy, language, themes, valence, danceability, "mock-provider"));
  }

  private String detectLanguage(TrackContext context) {
    String text = (context.artist() != null ? context.artist() : "")
        + (context.title() != null ? context.title() : "");
    for (char c : text.toCharArray()) {
      if (c >= '\u0400' && c <= '\u04FF') return "ru";
      if (c >= '\u3040' && c <= '\u30FF') return "ja";
      if (c >= '\uAC00' && c <= '\uD7AF') return "ko";
      if (c >= '\u4E00' && c <= '\u9FFF') return "zh";
    }
    return "en";
  }

  private String deriveThemes(TrackContext context, String mood) {
    return switch (mood) {
      case "happy" -> "joy,sunshine";
      case "sad" -> "loss,rain";
      case "calm" -> "nature,peace";
      case "energetic" -> "party,dance";
      case "aggressive" -> "power,rebellion";
      case "romantic" -> "love,passion";
      case "melancholic" -> "memory,longing";
      case "nostalgic" -> "childhood,past";
      default -> "life";
    };
  }

  @Override
  public String getProviderName() {
    return "mock";
  }

  @Override
  public String getModelName() {
    return "mock-v1";
  }

  @Override
  public boolean isEnabled() {
    return true;
  }
}
