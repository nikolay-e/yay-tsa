#!/usr/bin/env python3
"""Per-user taste clustering: PCA + dynamic-k k-means over each user's affinity-track
MERT embeddings, writing core_v2_ml.taste_clusters (one row per taste facet, with the
medoid as representative_track_id).

The number of clusters is NOT hardcoded — it scales with how many tracks the user has
(coverage: ~1 facet per TRACKS_PER_CLUSTER tracks), then PCA collapses the 768-dim space
to the components that hold REPRESENTATIVE_VARIANCE so k-means finds real structure
instead of high-dim noise. Clusters smaller than MIN_CLUSTER_SIZE are dropped (outliers).

Radio reads these representatives so the queue spans the user's real taste facets
(metal / russian-rap / folk-metal / …) instead of one averaged centroid.

Runs as a CronJob (scheduled re-cluster) or manually. Needs DB creds.
"""

import logging
import os
import sys
import time

import numpy as np
import psycopg2
from psycopg2.extras import execute_values
from sklearn.cluster import KMeans
from sklearn.decomposition import PCA

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("taste_clusters")

MIN_TRACKS = int(os.getenv("TASTE_MIN_TRACKS", "15"))
TRACKS_PER_CLUSTER = int(os.getenv("TASTE_TRACKS_PER_CLUSTER", "70"))
MIN_CLUSTER_SIZE = int(os.getenv("TASTE_MIN_CLUSTER_SIZE", "5"))
REPRESENTATIVE_VARIANCE = float(os.getenv("TASTE_PCA_VARIANCE", "0.85"))
USER_LIMIT = int(os.getenv("TASTE_USER_LIMIT", "0"))  # 0 = all users


def _db_connect():
    # Retry rides the Cilium identity-propagation window (fresh pod's first connect is RST).
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


def _load_user_embeddings(conn):
    """{user_id: [(track_id, mert_vec), ...]} for users with MERT-embedded affinity tracks."""
    by_user: dict[str, list] = {}
    with conn.cursor("taste_stream") as cur:  # server-side cursor: don't buffer all in client
        cur.itersize = 2000
        cur.execute(
            "SELECT a.user_id, a.track_id, f.embedding_mert "
            "FROM core_v2_ml.user_track_affinity a "
            "JOIN core_v2_ml.track_features f ON f.track_id = a.track_id "
            "WHERE f.embedding_mert IS NOT NULL "
            "ORDER BY a.user_id"
        )
        for user_id, track_id, emb in cur:
            v = np.fromstring(str(emb).strip("[]"), sep=",", dtype=np.float32)
            if v.size == 768:
                by_user.setdefault(str(user_id), []).append((str(track_id), v))
    return by_user


def _cluster(vecs):
    """Returns list of (member_indices, centroid_768) for clusters >= MIN_CLUSTER_SIZE."""
    mat = np.vstack([v for _, v in vecs])
    unit = mat / np.linalg.norm(mat, axis=1, keepdims=True)  # unit vectors -> euclidean == cosine
    n = len(unit)
    reduced = PCA(
        n_components=REPRESENTATIVE_VARIANCE, svd_solver="full", random_state=0
    ).fit_transform(unit)
    kmax = max(2, min(15, n // MIN_CLUSTER_SIZE))
    k = min(kmax, max(2, round(n / TRACKS_PER_CLUSTER)))
    km = KMeans(n_clusters=k, n_init="auto", random_state=0).fit(reduced)
    out = []
    for c in range(k):
        idx = np.nonzero(km.labels_ == c)[0]
        if len(idx) < MIN_CLUSTER_SIZE:
            continue
        cen = reduced[idx].mean(0)
        medoid = idx[np.argmin(((reduced[idx] - cen) ** 2).sum(1))]
        centroid_768 = mat[idx].mean(0)  # raw mean in MERT space (for stored centroid)
        out.append((int(medoid), idx, centroid_768))
    out.sort(key=lambda t: -len(t[1]))  # biggest facet first
    return out


def _vector(v):
    return "[" + ",".join(str(float(x)) for x in v) + "]"


def _write(conn, user_id, clusters, vecs):
    with conn.cursor() as cur:
        cur.execute("DELETE FROM core_v2_ml.taste_clusters WHERE user_id = %s", (user_id,))
        rows = [
            (user_id, cid, len(idx), vecs[medoid][0], _vector(centroid))
            for cid, (medoid, idx, centroid) in enumerate(clusters)
        ]
        if rows:
            execute_values(
                cur,
                "INSERT INTO core_v2_ml.taste_clusters "
                "(user_id, cluster_id, size, representative_track_id, centroid_mert, updated_at) "
                "VALUES %s",
                rows,
                template="(%s,%s,%s,%s,%s,now())",
            )
    conn.commit()


def main():
    conn = _db_connect()
    try:
        by_user = _load_user_embeddings(conn)
        users = [u for u, v in by_user.items() if len(v) >= MIN_TRACKS]
        if USER_LIMIT > 0:
            users = users[:USER_LIMIT]
        log.info("clustering %d eligible user(s) (>= %d tracks)", len(users), MIN_TRACKS)
        total_clusters = 0
        started = time.time()
        for user_id in users:
            vecs = by_user[user_id]
            try:
                clusters = _cluster(vecs)
                _write(conn, user_id, clusters, vecs)
                total_clusters += len(clusters)
                log.info("user %s: %d tracks -> %d clusters", user_id, len(vecs), len(clusters))
            except Exception as exc:  # keep going on per-user failures
                conn.rollback()
                log.warning("user %s failed: %s", user_id, exc)
        log.info(
            "done in %.1fs: %d clusters across %d users",
            time.time() - started,
            total_clusters,
            len(users),
        )
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
