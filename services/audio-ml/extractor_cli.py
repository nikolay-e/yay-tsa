#!/usr/bin/env python3
"""Batch feature extractor: fills core_v2_ml.track_features for tracks that lack it.

Usage:
    python3 extractor_cli.py            # process every track missing features
    python3 extractor_cli.py <uuid>     # process a single track by entity id

Reuses the Essentia + CLAP/MERT extractors from the audio-ml service and writes
directly to PostgreSQL. Intended to run as a Kubernetes CronJob on the
feature-extractor (essentia) image, which already bakes the Essentia models.

DB connections are short-lived (open per query/write, never held across the
multi-minute model load / extraction) — the shared-database pgbouncer pooler
closes idle connections, so a long-held connection dies before the write.
"""

import argparse
import logging
import os
import sys
import time

import db
from extractor.embedding_extractor import DualEmbeddingExtractor
from extractor.essentia_analyzer import EssentiaAnalyzer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("extractor_cli")

EXTRACTOR_VERSION = os.getenv("EXTRACTOR_VERSION", "essentia-1.1+clap+mert")
MUSIC_PATH = os.getenv("MUSIC_PATH", "/media")
MODELS_DIR = os.getenv("ESSENTIA_MODELS_DIR", "/app/models")
BATCH_LIMIT = int(os.getenv("EXTRACT_BATCH_LIMIT", "0"))  # 0 = no limit
# Tracks that fail extraction this many times are parked (skipped by the pending query) so a
# head-of-queue cluster of undecodable files can't permanently eat the whole nightly batch
# and starve every track behind it. Same pattern as karaoke_fail_count.
MAX_ATTEMPTS = int(os.getenv("EXTRACT_MAX_ATTEMPTS", "3"))


def _pending_tracks(single_id):
    conn = db.connect()
    try:
        with conn.cursor() as cur:
            if single_id:
                cur.execute(
                    "SELECT id, source_path FROM core_v2_library.entities "
                    "WHERE entity_type = 'TRACK' AND source_path IS NOT NULL AND id = %s",
                    (single_id,),
                )
            else:
                # Audiobooks (genre=Audiobook) are excluded: spoken-word embeddings have no music
                # value (every recommendation surface already drops audiobooks) and would only leak
                # into the similarity pools that seed radio and the LLM-DJ. Matches the exclusion in
                # the Kotlin MlFeatureExtractor / findMlUnprocessedTrackIds.
                sql = (
                    "SELECT e.id, e.source_path FROM core_v2_library.entities e "
                    "LEFT JOIN core_v2_ml.track_features f ON f.track_id = e.id "
                    "WHERE e.entity_type = 'TRACK' AND e.source_path IS NOT NULL "
                    "AND f.track_id IS NULL "
                    "AND NOT EXISTS ("
                    "SELECT 1 FROM core_v2_library.entity_genres eg "
                    "JOIN core_v2_library.genres g ON g.id = eg.genre_id "
                    "WHERE eg.entity_id = e.id AND lower(g.name) IN ('audiobook', 'audiobooks')) "
                    "AND NOT EXISTS ("
                    "SELECT 1 FROM core_v2_ml.extraction_failures x "
                    "WHERE x.track_id = e.id AND x.fail_count >= %(max_attempts)s) "
                    "ORDER BY e.created_at"
                )
                if BATCH_LIMIT > 0:
                    sql += f" LIMIT {BATCH_LIMIT}"
                cur.execute(sql, {"max_attempts": MAX_ATTEMPTS})
            return cur.fetchall()
    finally:
        conn.close()


INSERT_SQL = """
INSERT INTO core_v2_ml.track_features (
    track_id, bpm, bpm_confidence, musical_key, key_confidence, energy,
    loudness_integrated, loudness_range, average_loudness, valence, arousal,
    danceability, vocal_instrumental, spectral_complexity, dissonance, onset_rate,
    embedding_discogs, embedding_musicnn, embedding_clap, embedding_mert,
    extracted_at, extractor_version
) VALUES (
    %(track_id)s, %(bpm)s, %(bpm_confidence)s, %(musical_key)s, %(key_confidence)s, %(energy)s,
    %(loudness_integrated)s, %(loudness_range)s, %(average_loudness)s, %(valence)s, %(arousal)s,
    %(danceability)s, %(vocal_instrumental)s, %(spectral_complexity)s, %(dissonance)s, %(onset_rate)s,
    %(embedding_discogs)s, %(embedding_musicnn)s, %(embedding_clap)s, %(embedding_mert)s,
    now(), %(extractor_version)s
)
ON CONFLICT (track_id) DO NOTHING
"""


def _write_features(row):
    conn = db.connect()
    try:
        with conn.cursor() as cur:
            cur.execute(INSERT_SQL, row)
            cur.execute(
                "DELETE FROM core_v2_ml.extraction_failures WHERE track_id = %s",
                (row["track_id"],),
            )
        conn.commit()
    finally:
        conn.close()


def _record_failure(track_id, error):
    conn = db.connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO core_v2_ml.extraction_failures (track_id, last_error) "
                "VALUES (%s, %s) "
                "ON CONFLICT (track_id) DO UPDATE SET "
                "fail_count = extraction_failures.fail_count + 1, "
                "last_error = EXCLUDED.last_error, updated_at = now()",
                (track_id, str(error)[:2000]),
            )
        conn.commit()
    finally:
        conn.close()


def _extract_one(essentia, embedder, track_id, source_path):
    audio_path = os.path.join(MUSIC_PATH, source_path)
    if not os.path.isfile(audio_path):
        log.warning("track %s: file not found at %s", track_id, audio_path)
        return f"file not found: {audio_path}"

    essentia_result = essentia.extract(audio_path)
    features = essentia_result.get("features")
    if not features:
        log.warning("track %s: essentia returned no features", track_id)
        return "essentia returned no features"
    embeddings = embedder.extract(audio_path)

    row = {
        "track_id": track_id,
        "bpm": features.get("bpm"),
        "bpm_confidence": features.get("bpm_confidence"),
        "musical_key": features.get("key"),
        "key_confidence": features.get("key_confidence"),
        "energy": features.get("energy"),
        "loudness_integrated": features.get("loudness_integrated"),
        "loudness_range": features.get("loudness_range"),
        "average_loudness": features.get("average_loudness"),
        "valence": features.get("valence"),
        "arousal": features.get("arousal"),
        "danceability": features.get("danceability"),
        "vocal_instrumental": features.get("vocal_instrumental"),
        "spectral_complexity": features.get("spectral_complexity"),
        "dissonance": features.get("dissonance"),
        "onset_rate": features.get("onset_rate"),
        "embedding_discogs": db.vector(essentia_result.get("embedding_discogs")),
        "embedding_musicnn": db.vector(essentia_result.get("embedding_musicnn")),
        "embedding_clap": db.vector(embeddings.get("embedding_clap")),
        "embedding_mert": db.vector(embeddings.get("embedding_mert")),
        "extractor_version": EXTRACTOR_VERSION,
    }
    _write_features(row)
    return None


def main():
    parser = argparse.ArgumentParser(
        description="Fill core_v2_ml.track_features for tracks that lack it."
    )
    parser.add_argument(
        "track_id",
        nargs="?",
        default=None,
        help="process a single track by entity id (default: all pending tracks)",
    )
    args = parser.parse_args()
    single_id = args.track_id
    pending = _pending_tracks(single_id)
    log.info("found %d track(s) to process", len(pending))
    if not pending:
        return 0

    # Load models once, AFTER the pending query — model init takes minutes and
    # must not hold a DB connection open (pooler would close it).
    essentia = EssentiaAnalyzer(MODELS_DIR)
    embedder = DualEmbeddingExtractor()

    processed = 0
    failed = 0
    started = time.time()
    for track_id, source_path in pending:
        try:
            error = _extract_one(essentia, embedder, str(track_id), source_path)
            if error is None:
                processed += 1
            else:
                failed += 1
                _record_failure(str(track_id), error)
        except Exception as exc:  # keep the batch going on per-track errors
            failed += 1
            log.warning("track %s failed: %s", track_id, exc)
            _record_failure(str(track_id), exc)
    log.info(
        "done: processed=%d failed=%d in %.1fs",
        processed,
        failed,
        time.time() - started,
    )
    return 1 if (single_id and processed == 0) else 0


if __name__ == "__main__":
    sys.exit(main())
