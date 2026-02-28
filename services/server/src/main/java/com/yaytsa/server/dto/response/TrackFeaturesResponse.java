package com.yaytsa.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record TrackFeaturesResponse(
    @JsonProperty("track_id") String trackId,
    @JsonProperty("bpm") Float bpm,
    @JsonProperty("bpm_confidence") Float bpmConfidence,
    @JsonProperty("musical_key") String musicalKey,
    @JsonProperty("key_confidence") Float keyConfidence,
    @JsonProperty("energy") Float energy,
    @JsonProperty("loudness_integrated") Float loudnessIntegrated,
    @JsonProperty("loudness_range") Float loudnessRange,
    @JsonProperty("average_loudness") Float averageLoudness,
    @JsonProperty("valence") Float valence,
    @JsonProperty("arousal") Float arousal,
    @JsonProperty("danceability") Float danceability,
    @JsonProperty("vocal_instrumental") Float vocalInstrumental,
    @JsonProperty("spectral_complexity") Float spectralComplexity,
    @JsonProperty("dissonance") Float dissonance,
    @JsonProperty("onset_rate") Float onsetRate,
    @JsonProperty("extracted_at") OffsetDateTime extractedAt,
    @JsonProperty("extractor_version") String extractorVersion) {}
