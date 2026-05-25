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

import logging
import os
import sys
import time

import psycopg2

from extractor.embedding_extractor import DualEmbeddingExtractor
from extractor.essentia_analyzer import EssentiaAnalyzer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("extractor_cli")

EXTRACTOR_VERSION = os.getenv("EXTRACTOR_VERSION", "essentia-1.1+clap+mert")
MUSIC_PATH = os.getenv("MUSIC_PATH", "/media")
MODELS_DIR = os.getenv("ESSENTIA_MODELS_DIR", "/app/models")
BATCH_LIMIT = int(os.getenv("EXTRACT_BATCH_LIMIT", "0"))  # 0 = no limit


def _db_connect():
    # A freshly-scheduled pod's Cilium identity can lag the destination's policy
    # enforcement for a few seconds, so the first connect to the pooler is refused
    # (RST) before the identity propagates. Retry with backoff to ride that window.
    last_err = None
    for attempt in range(1, 7):
        try:
            return psycopg2.connect(
                host=os.getenv("POSTGRES_HOST") or os.getenv("DB_HOST", "localhost"),
                port=os.getenv("POSTGRES_PORT") or os.getenv("DB_PORT", "5432"),
                dbname=os.getenv("POSTGRES_DB") or os.getenv("DB_NAME", "yaytsa_production"),
                user=os.getenv("POSTGRES_USER") or os.getenv("DB_USERNAME", "yaytsa_production"),
                password=os.getenv("POSTGRES_PASSWORD") or os.getenv("DB_PASSWORD", ""),
            )
        except psycopg2.OperationalError as exc:
            last_err = exc
            log.warning("DB connect attempt %d/6 failed: %s", attempt, exc)
            time.sleep(5)
    raise last_err or RuntimeError("DB connection failed after retries")


def _vector(values):
    if not values:
        return None
    return "[" + ",".join(str(float(v)) for v in values) + "]"


def _pending_tracks(single_id):
    conn = _db_connect()
    try:
        with conn.cursor() as cur:
            if single_id:
                cur.execute(
                    "SELECT id, source_path FROM core_v2_library.entities "
                    "WHERE entity_type = 'TRACK' AND source_path IS NOT NULL AND id = %s",
                    (single_id,),
                )
            else:
                sql = (
                    "SELECT e.id, e.source_path FROM core_v2_library.entities e "
                    "LEFT JOIN core_v2_ml.track_features f ON f.track_id = e.id "
                    "WHERE e.entity_type = 'TRACK' AND e.source_path IS NOT NULL "
                    "AND f.track_id IS NULL ORDER BY e.created_at"
                )
                if BATCH_LIMIT > 0:
                    sql += f" LIMIT {BATCH_LIMIT}"
                cur.execute(sql)
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
    conn = _db_connect()
    try:
        with conn.cursor() as cur:
            cur.execute(INSERT_SQL, row)
        conn.commit()
    finally:
        conn.close()


def _extract_one(essentia, embedder, track_id, source_path):
    audio_path = os.path.join(MUSIC_PATH, source_path)
    if not os.path.isfile(audio_path):
        log.warning("track %s: file not found at %s", track_id, audio_path)
        return False

    essentia_result = essentia.extract(audio_path)
    features = essentia_result.get("features")
    if not features:
        log.warning("track %s: essentia returned no features", track_id)
        return False
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
        "embedding_discogs": _vector(essentia_result.get("embedding_discogs")),
        "embedding_musicnn": _vector(essentia_result.get("embedding_musicnn")),
        "embedding_clap": _vector(embeddings.get("embedding_clap")),
        "embedding_mert": _vector(embeddings.get("embedding_mert")),
        "extractor_version": EXTRACTOR_VERSION,
    }
    _write_features(row)
    return True


def main():
    single_id = sys.argv[1] if len(sys.argv) > 1 else None
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
            if _extract_one(essentia, embedder, str(track_id), source_path):
                processed += 1
            else:
                failed += 1
        except Exception as exc:  # keep the batch going on per-track errors
            failed += 1
            log.warning("track %s failed: %s", track_id, exc)
    log.info(
        "done: processed=%d failed=%d in %.1fs",
        processed,
        failed,
        time.time() - started,
    )
    return 1 if (single_id and processed == 0) else 0


if __name__ == "__main__":
    sys.exit(main())
