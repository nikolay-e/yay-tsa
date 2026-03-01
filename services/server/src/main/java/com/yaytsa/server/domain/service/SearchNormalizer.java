package com.yaytsa.server.domain.service;

import com.ibm.icu.text.Transliterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SearchNormalizer {

  private static final String CYRILLIC_TO_LATIN_ID = "Russian-Latin/BGN";
  private static final String LATIN_TO_CYRILLIC_ID = "Latin-Russian/BGN";
  private static final String ANY_TO_ASCII_ID = "Any-Latin; Latin-ASCII";

  public String buildSearchText(
      String itemName, String artistName, String albumName, List<String> genres) {
    StringBuilder sb = new StringBuilder();
    appendIfPresent(sb, itemName);
    appendIfPresent(sb, artistName);
    appendIfPresent(sb, albumName);
    if (genres != null) {
      for (String genre : genres) {
        appendIfPresent(sb, genre);
      }
    }
    String combined = sb.toString().toLowerCase(Locale.ROOT);
    String transliterated = transliterateToLatin(combined);
    if (!transliterated.equals(combined)) {
      return combined + " " + transliterated;
    }
    return combined;
  }

  public List<String> generateSearchVariants(String searchTerm) {
    if (searchTerm == null || searchTerm.isBlank()) {
      return List.of();
    }

    String normalized = searchTerm.toLowerCase(Locale.ROOT).trim();
    List<String> variants = new ArrayList<>();
    variants.add(normalized);

    if (containsCyrillic(normalized)) {
      String latin = transliterateToLatin(normalized);
      if (!latin.equals(normalized)) {
        variants.add(latin);
      }
    }

    if (containsLatin(normalized)) {
      Transliterator latToCyr = Transliterator.getInstance(LATIN_TO_CYRILLIC_ID);
      String cyrillic = latToCyr.transliterate(normalized);
      if (!cyrillic.equals(normalized)) {
        variants.add(cyrillic);
      }
    }

    Transliterator anyToAscii = Transliterator.getInstance(ANY_TO_ASCII_ID);
    String ascii = anyToAscii.transliterate(normalized);
    if (!ascii.equals(normalized) && !variants.contains(ascii)) {
      variants.add(ascii);
    }

    return variants;
  }

  private String transliterateToLatin(String input) {
    Transliterator cyrToLat = Transliterator.getInstance(CYRILLIC_TO_LATIN_ID);
    Transliterator anyToAscii = Transliterator.getInstance(ANY_TO_ASCII_ID);
    String yoNormalized = input.replace('\u0451', '\u0435').replace('\u0401', '\u0415');
    String latin = cyrToLat.transliterate(yoNormalized);
    return anyToAscii.transliterate(latin).toLowerCase(Locale.ROOT);
  }

  private void appendIfPresent(StringBuilder sb, String value) {
    if (value != null && !value.isBlank()) {
      if (!sb.isEmpty()) {
        sb.append(' ');
      }
      sb.append(value.trim());
    }
  }

  private boolean containsCyrillic(String input) {
    return input
        .codePoints()
        .anyMatch(cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CYRILLIC);
  }

  private boolean containsLatin(String input) {
    return input
        .codePoints()
        .anyMatch(
            cp ->
                Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.BASIC_LATIN
                    && Character.isLetter(cp));
  }
}
