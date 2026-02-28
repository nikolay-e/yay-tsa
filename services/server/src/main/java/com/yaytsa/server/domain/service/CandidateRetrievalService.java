package com.yaytsa.server.domain.service;

import com.yaytsa.server.infrastructure.persistence.entity.TrackFeaturesEntity;
import com.yaytsa.server.infrastructure.persistence.repository.PlayHistoryRepository;
import com.yaytsa.server.infrastructure.persistence.repository.TrackFeaturesRepository;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CandidateRetrievalService {

  private static final Logger log = LoggerFactory.getLogger(CandidateRetrievalService.class);

  private final TrackFeaturesRepository trackFeaturesRepository;
  private final PlayHistoryRepository playHistoryRepository;

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
      int limit) {}

  public List<TrackCandidate> searchLibrary(LibrarySearchFilters filters) {
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
            filters.limit());
    var candidates = rows.stream().map(this::mapLibraryRow).toList();
    if (filters.excludeArtists() != null && !filters.excludeArtists().isEmpty())
      return candidates.stream()
          .filter(c -> c.artistName() == null || !filters.excludeArtists().contains(c.artistName()))
          .toList();
    return candidates;
  }

  public List<TrackCandidate> findSimilarTracks(UUID referenceTrackId, int limit) {
    TrackFeaturesEntity features =
        trackFeaturesRepository.findByTrackId(referenceTrackId).orElse(null);
    if (features == null || features.getEmbeddingDiscogs() == null) {
      log.debug("No embedding found for track {}, falling back to empty results", referenceTrackId);
      return Collections.emptyList();
    }
    var rows =
        trackFeaturesRepository.findSimilarTracks(
            referenceTrackId, formatEmbedding(features.getEmbeddingDiscogs()), limit);
    return rows.stream().map(this::mapSimilarityRow).toList();
  }

  public List<UUID> getRecentlyOverplayed(UUID userId, int hours) {
    return playHistoryRepository.findOverplayedTrackIds(
        userId, OffsetDateTime.now().minusHours(hours));
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
      int limit) {}

  public List<TrackCandidate> findNeverPlayedTracks(UUID userId, NeverPlayedFilters filters) {
    var rows =
        trackFeaturesRepository.findNeverPlayedTracks(
            userId,
            filters.energyMin(),
            filters.energyMax(),
            filters.bpmMin(),
            filters.bpmMax(),
            filters.valenceMin(),
            filters.valenceMax(),
            filters.limit());
    return rows.stream().map(this::mapLibraryRow).toList();
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
        null,
        toFloat(row[3]),
        toFloat(row[4]),
        toFloat(row[5]),
        toFloat(row[6]),
        null,
        null,
        null,
        toDouble(row[7]));
  }

  private static String formatEmbedding(float[] embedding) {
    return IntStream.range(0, embedding.length)
        .mapToObj(i -> String.valueOf(embedding[i]))
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static Float toFloat(Object val) {
    return val instanceof Number n ? n.floatValue() : null;
  }

  private static Double toDouble(Object val) {
    return val instanceof Number n ? n.doubleValue() : null;
  }
}
