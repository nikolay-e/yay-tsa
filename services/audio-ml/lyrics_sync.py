#!/usr/bin/env python3
"""Backfill/upgrade time-synced .lrc sidecar files from LRCLIB.

For every track whose sidecar .lrc is missing or PLAIN (no [mm:ss] timestamps),
fetch a synced version from lrclib.net and write/overwrite the .lrc. Synced files
are left as-is. When LRCLIB has no synced match a plain file is left untouched —
this job only ever upgrades plain->synced, never degrades or deletes.

Runs as a CronJob (scheduled re-check) or manually. Needs RW /media + DB creds.
Re-checking is the point: as LRCLIB grows, previously-plain tracks get upgraded.
"""

import logging
import os
import re
import sys
import time
from pathlib import Path

import requests

import db

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("lyrics_sync")

MUSIC_PATH = os.getenv("MUSIC_PATH", "/media")
LRCLIB_URL = os.getenv("LRCLIB_BASE_URL", "https://lrclib.net").rstrip("/")
BATCH_LIMIT = int(os.getenv("LYRICS_BATCH_LIMIT", "0"))  # 0 = all tracks
USER_AGENT = "yay-tsa (https://github.com/nikolay-e/yay-tsa)"
SYNC_RE = re.compile(r"\[\d{1,2}:\d{2}")
NUM_PREFIX_RE = re.compile(r"^(\d{1,3})\s*[-._]\s*")

TRACK_QUERY = """
SELECT e.source_path, e.name AS title, ar.name AS artist, al.name AS album, t.duration_ms
FROM core_v2_library.entities e
JOIN core_v2_library.audio_tracks t ON t.entity_id = e.id
LEFT JOIN core_v2_library.entities al ON al.id = t.album_id
LEFT JOIN core_v2_library.entities ar ON ar.id = t.album_artist_id
WHERE e.entity_type = 'TRACK' AND e.source_path IS NOT NULL
ORDER BY e.created_at
"""


def _load_tracks():
    conn = db.connect()
    try:
        with conn.cursor() as cur:
            sql = TRACK_QUERY + (f" LIMIT {BATCH_LIMIT}" if BATCH_LIMIT > 0 else "")
            cur.execute(sql)
            return cur.fetchall()
    finally:
        conn.close()


def _is_synced(text):
    return bool(SYNC_RE.search(text))


def _existing_lrc(audio_path):
    """Mirror the backend's findSidecarLrc: <parent>/.lyrics/<basename>.lrc, with a
    loose track-number-prefix fallback. Returns the path of an existing .lrc or None."""
    lyrics_dir = Path(audio_path).parent / ".lyrics"
    if not lyrics_dir.is_dir():
        return None
    base = Path(audio_path).stem
    direct = lyrics_dir / (base + ".lrc")
    if direct.is_file():
        return str(direct)
    m = NUM_PREFIX_RE.match(base)
    if m:
        prefix = m.group(0)
        for path in sorted(lyrics_dir.glob("*.lrc")):
            if path.name.startswith(prefix):
                return str(path)
    return None


def _get_json(path, params):
    try:
        r = requests.get(
            f"{LRCLIB_URL}{path}",
            params=params,
            headers={"User-Agent": USER_AGENT},
            timeout=10,
        )
        return r.json() if r.status_code == 200 else None
    except requests.RequestException as exc:
        log.warning("lrclib %s failed: %s", path, exc)
        return None


def _fetch_synced(title, artist, album, duration_ms):
    if not title or not artist:
        return None
    params = {"track_name": title, "artist_name": artist}
    if album:
        params["album_name"] = album
    if duration_ms:
        params["duration"] = int(duration_ms / 1000)

    exact = _get_json("/api/get", params)
    if exact and exact.get("syncedLyrics"):
        return exact["syncedLyrics"]
    for item in _get_json("/api/search", {"track_name": title, "artist_name": artist}) or []:
        if item.get("syncedLyrics"):
            return item["syncedLyrics"]
    return None


def _process(track):
    source_path, title, artist, album, duration_ms = track
    audio_path = str(Path(MUSIC_PATH) / source_path)
    if not Path(audio_path).is_file():
        return "missing_file"

    existing = _existing_lrc(audio_path)
    if existing is not None:
        try:
            with open(existing, encoding="utf-8", errors="replace") as fh:
                if _is_synced(fh.read()):
                    return "already_synced"
        except OSError:
            pass  # unreadable -> treat as needing refresh

    synced = _fetch_synced(title, artist, album, duration_ms)
    if not synced:
        return "no_synced_available"

    target = (
        Path(existing)
        if existing
        else (Path(audio_path).parent / ".lyrics" / (Path(audio_path).stem + ".lrc"))
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "w", encoding="utf-8") as fh:
        fh.write(synced)
    return "upgraded" if existing else "created"


def main():
    tracks = _load_tracks()
    log.info("checking %d track(s) for synced lyrics", len(tracks))
    counts: dict[str, int] = {}
    started = time.time()
    for track in tracks:
        try:
            outcome = _process(track)
        except Exception as exc:  # keep the batch going on per-track errors
            outcome = "error"
            log.warning("track %s failed: %s", track[0], exc)
        counts[outcome] = counts.get(outcome, 0) + 1
    log.info("done in %.1fs: %s", time.time() - started, dict(sorted(counts.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
