package com.yaytsa.server.domain.service;

import com.yaytsa.server.domain.util.EmbeddingUtils;
import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
public class CandidateRetrievalService {

  private static final long FEATURE_COUNT_TTL_MS = 300_000;

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final PlayHistoryRepository playHistoryRepository;

  @Value("${yaytsa.adaptive-dj.similarity.exact-search-threshold:500}")
  private int exactSearchThreshold;

  private volatile long cachedFeatureCount = -1;
  private volatile long featureCountTimestamp = 0;

  public CandidateRetrievalService(
      TrackFeaturesRepository trackFeaturesRepository,
      PlayHistoryRepository playHistoryRepository) {
    this.trackFeaturesRepository = trackFeaturesRepository;
    this.playHistoryRepository = playHistoryRepository;
  }

  public record TrackCandidate(
      UUID id,
      String name,
      String artistName,
      String albumName,
      Float bpm,
      Float energy,
      Float valence,
      Float arousal,
      Float danceability,
      Float vocalInstrumental,
      String musicalKey,
      Double similarity) {}

  public record LibrarySearchFilters(
      Float energyMin,
      Float energyMax,
      Float bpmMin,
      Float bpmMax,
      Float valenceMin,
      Float valenceMax,
      Float arousalMin,
      Float arousalMax,
      Float vocalMax,
      List<String> excludeArtists,
      List<String> excludeGenres,
      List<String> includeGenres,
      int limit) {}

  public List<TrackCandidate> searchLibrary(LibrarySearchFilters filters) {
    List<TrackCandidate> genreCandidates = List.of();
    if (filters.includeGenres() != null && !filters.includeGenres().isEmpty()) {
      List<String> lowercased = filters.includeGenres().stream().map(String::toLowerCase).toList();
      var genreRows =
          trackFeaturesRepository.searchLibraryByGenre(
              lowercased,
              filters.energyMin(),
              filters.energyMax(),
              filters.valenceMin(),
              filters.valenceMax(),
              filters.limit());
      genreCandidates = genreRows.stream().map(this::mapLibraryRow).toList();
    }

    int remaining = filters.limit() - genreCandidates.size();
    List<TrackCandidate> generalCandidates = List.of();
    if (remaining > 0) {
      var rows =
          trackFeaturesRepository.searchLibrary(
              filters.energyMin(),
              filters.energyMax(),
              filters.bpmMin(),
              filters.bpmMax(),
              filters.valenceMin(),
              filters.valenceMax(),
              filters.arousalMin(),
              filters.arousalMax(),
              filters.vocalMax(),
              remaining * 2);
      generalCandidates = rows.stream().map(this::mapLibraryRow).toList();
    }

    Set<UUID> genreIds =
        genreCandidates.stream()
            .map(TrackCandidate::id)
            .collect(java.util.stream.Collectors.toSet());
    var merged = new java.util.ArrayList<>(genreCandidates);
    for (var c : generalCandidates) {
      if (!genreIds.contains(c.id()) && merged.size() < filters.limit()) {
        merged.add(c);
      }
    }

    List<TrackCandidate> candidates = merged;
    if (filters.excludeArtists() != null && !filters.excludeArtists().isEmpty()) {
      candidates =
          candidates.stream()
              .filter(
                  c -> c.artistName() == null || !filters.excludeArtists().contains(c.artistName()))
              .toList();
    }
    if (filters.excludeGenres() != null && !filters.excludeGenres().isEmpty()) {
      List<String> lowercasedGenres =
          filters.excludeGenres().stream().map(String::toLowerCase).toList();
      Set<UUID> genreExcludedIds =
          trackFeaturesRepository.findTrackIdsByGenreNames(lowercasedGenres);
      candidates = candidates.stream().filter(c -> !genreExcludedIds.contains(c.id())).toList();
    }
    return candidates;
  }

  public List<TrackCandidate> findSimilarTracks(UUID referenceTrackId, int limit) {
    TrackFeaturesEntity features =
        trackFeaturesRepository.findByTrackId(referenceTrackId).orElse(null);
    if (features == null || features.getEmbeddingDiscogs() == null) {
      log.debug("No embedding found for track {}, falling back to empty results", referenceTrackId);
      return Collections.emptyList();
    }

    long featureCount = getFeatureCount();
    boolean useExact = featureCount < exactSearchThreshold;
    String embedding = EmbeddingUtils.format(features.getEmbeddingDiscogs());

    if (useExact) {
      log.debug(
          "Using exact vector search ({} tracks < {} threshold)",
          featureCount,
          exactSearchThreshold);
    }

    var rows =
        useExact
            ? trackFeaturesRepository.findSimilarTracksExact(referenceTrackId, embedding, limit)
            : trackFeaturesRepository.findSimilarTracks(referenceTrackId, embedding, limit);
    return rows.stream().map(this::mapSimilarityRow).toList();
  }

  public List<UUID> getRecentlyOverplayed(UUID userId, int hours) {
    return playHistoryRepository.findOverplayedTrackIds(
        userId, OffsetDateTime.now().minusHours(hours));
  }

  public List<UUID> getRecentlyPlayedTrackIds(UUID userId, int hours) {
    long windowMs = (long) hours * 3_600_000L;
    return playHistoryRepository.findDistinctTrackIdsWithinPlaybackWindow(userId, windowMs);
  }

  public List<TrackCandidate> getTrackDetails(List<UUID> trackIds) {
    if (trackIds == null || trackIds.isEmpty()) return Collections.emptyList();
    return trackFeaturesRepository.findTrackDetailsById(trackIds).stream()
        .map(this::mapLibraryRow)
        .toList();
  }

  public record NeverPlayedFilters(
      Float energyMin,
      Float energyMax,
      Float bpmMin,
      Float bpmMax,
      Float valenceMin,
      Float valenceMax,
      List<String> includeGenres,
      int limit) {}

  public List<TrackCandidate> findNeverPlayedTracks(UUID userId, NeverPlayedFilters filters) {
    List<TrackCandidate> genreCandidates = List.of();
    if (filters.includeGenres() != null && !filters.includeGenres().isEmpty()) {
      List<String> lowercased = filters.includeGenres().stream().map(String::toLowerCase).toList();
      var genreRows =
          trackFeaturesRepository.findNeverPlayedTracksByGenre(
              userId, lowercased, filters.energyMin(), filters.energyMax(), filters.limit());
      genreCandidates = genreRows.stream().map(this::mapLibraryRow).toList();
    }

    int remaining = filters.limit() - genreCandidates.size();
    List<TrackCandidate> generalCandidates = List.of();
    if (remaining > 0) {
      var rows =
          trackFeaturesRepository.findNeverPlayedTracks(
              userId,
              filters.energyMin(),
              filters.energyMax(),
              filters.bpmMin(),
              filters.bpmMax(),
              filters.valenceMin(),
              filters.valenceMax(),
              remaining * 2);
      generalCandidates = rows.stream().map(this::mapLibraryRow).toList();
    }

    Set<UUID> genreIds =
        genreCandidates.stream()
            .map(TrackCandidate::id)
            .collect(java.util.stream.Collectors.toSet());
    var merged = new java.util.ArrayList<>(genreCandidates);
    for (var c : generalCandidates) {
      if (!genreIds.contains(c.id()) && merged.size() < filters.limit()) {
        merged.add(c);
      }
    }
    return merged;
  }

  public List<TrackCandidate> getArtistTracks(String artistName) {
    return trackFeaturesRepository.findByArtistName(artistName).stream()
        .map(this::mapLibraryRow)
        .toList();
  }

  private TrackCandidate mapLibraryRow(Object[] row) {
    return new TrackCandidate(
        (UUID) row[0],
        (String) row[1],
        (String) row[2],
        (String) row[3],
        toFloat(row[4]),
        toFloat(row[5]),
        toFloat(row[6]),
        toFloat(row[7]),
        toFloat(row[8]),
        toFloat(row[9]),
        row.length > 11 ? (String) row[11] : (row.length > 10 ? (String) row[10] : null),
        null);
  }

  private TrackCandidate mapSimilarityRow(Object[] row) {
    return new TrackCandidate(
        (UUID) row[0],
        (String) row[1],
        (String) row[2],
        (String) row[3],
        toFloat(row[4]),
        toFloat(row[5]),
        toFloat(row[6]),
        toFloat(row[7]),
        toFloat(row[8]),
        null,
        null,
        toDouble(row[9]));
  }

  private long getFeatureCount() {
    long now = System.currentTimeMillis();
    if (cachedFeatureCount < 0 || now - featureCountTimestamp > FEATURE_COUNT_TTL_MS) {
      cachedFeatureCount = trackFeaturesRepository.count();
      featureCountTimestamp = now;
    }
    return cachedFeatureCount;
  }

  static TrackCandidate mapEmbeddingSimilarityRow(Object[] row) {
    return new TrackCandidate(
        (UUID) row[0],
        (String) row[1],
        (String) row[2],
        (String) row[3],
        toFloat(row[4]),
        toFloat(row[5]),
        toFloat(row[6]),
        toFloat(row[7]),
        toFloat(row[8]),
        null,
        null,
        toDouble(row[9]));
  }

  private static Float toFloat(Object val) {
    return val instanceof Number n ? n.floatValue() : null;
  }

  private static Double toDouble(Object val) {
    return val instanceof Number n ? n.doubleValue() : null;
  }
}
