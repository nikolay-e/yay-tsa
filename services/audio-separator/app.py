import os
import re
import time
import threading
import logging
import subprocess
import shutil
import unicodedata
from pathlib import Path
from contextlib import asynccontextmanager

import requests as http_requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "htdemucs")
OUTPUT_FORMAT = os.getenv("OUTPUT_FORMAT", "wav")
USE_CUDA = os.getenv("DEVICE", "cuda") == "cuda"
ALLOWED_MEDIA_ROOT = os.path.realpath(os.getenv("MEDIA_PATH", "/media"))
FFMPEG_TIMEOUT_SECONDS = 300

LRCLIB_BASE_URL = "https://lrclib.net/api"
LRCLIB_USER_AGENT = "yay-tsa/1.0.0 (https://yay-tsa.com)"
LRCLIB_TIMEOUT_SECONDS = 10
NEGATIVE_CACHE_MARKER = "[no lyrics found]"
NEGATIVE_CACHE_MAX_AGE_DAYS = 30

# ---------------------------------------------------------------------------
# Retry-capable HTTP session
# ---------------------------------------------------------------------------
def _create_http_session() -> http_requests.Session:
    session = http_requests.Session()
    retries = Retry(
        total=3,
        backoff_factor=0.5,
        status_forcelist=[429, 500, 502, 503, 504],
        allowed_methods=["GET"],
    )
    adapter = HTTPAdapter(max_retries=retries)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


_http_session = _create_http_session()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    log.info(f"Model {MODEL_NAME} will be loaded on first request")
    yield


app = FastAPI(title="Audio Separator Service", version="2.0.0", lifespan=lifespan)

_separator_lock = threading.Lock()


def create_separator(output_dir: str):
    from audio_separator.separator import Separator

    with _separator_lock:
        log.info(f"Creating Separator with model {MODEL_NAME}, output_dir={output_dir}")
        sep = Separator(
            output_dir=output_dir,
            output_format=OUTPUT_FORMAT,
        )
        sep.load_model(model_filename=MODEL_NAME)
        log.info(f"Model loaded, output_dir confirmed: {sep.output_dir}")
        return sep


class SeparationRequest(BaseModel):
    inputPath: str
    trackId: str


class SeparationResponse(BaseModel):
    instrumental_path: str
    vocal_path: str
    processing_time_ms: int


class LyricsRequest(BaseModel):
    artist: str
    title: str
    outputPath: str
    durationMs: int | None = None
    album: str | None = None
    force: bool = False


def _validate_media_path(user_path: str) -> Path:
    resolved = os.path.realpath(user_path)
    root_prefix = ALLOWED_MEDIA_ROOT if ALLOWED_MEDIA_ROOT.endswith(os.sep) else ALLOWED_MEDIA_ROOT + os.sep
    if not resolved.startswith(root_prefix):
        log.error(f"Path traversal attempt: {resolved}")
        raise HTTPException(status_code=403, detail="Invalid path")
    return Path(resolved)


class LyricsResponse(BaseModel):
    success: bool
    outputPath: str | None = None
    source: str | None = None
    synced: bool = False
    lyrics: str | None = None


class HealthResponse(BaseModel):
    status: str
    model: str
    device: str


@app.get("/health", response_model=HealthResponse)
def health_check():
    return HealthResponse(
        status="healthy",
        model=MODEL_NAME,
        device="cuda" if USE_CUDA else "cpu",
    )


@app.post("/api/separate", response_model=SeparationResponse)
def separate_audio(request: SeparationRequest):
    input_path = _validate_media_path(request.inputPath)
    track_id = request.trackId

    if not input_path.exists():
        raise HTTPException(status_code=404, detail=f"Input file not found: {input_path}")

    karaoke_dir = input_path.parent / ".karaoke"
    karaoke_path = karaoke_dir / f"{input_path.stem}_instrumental.{OUTPUT_FORMAT}"
    vocal_path = karaoke_dir / f"{input_path.stem}_vocals.{OUTPUT_FORMAT}"

    if karaoke_path.exists():
        log.info(f"Karaoke file already exists: {karaoke_path}")
        return SeparationResponse(
            instrumental_path=str(karaoke_path),
            vocal_path=str(vocal_path) if vocal_path.exists() else "",
            processing_time_ms=0,
        )

    start_time = time.time()
    song_name = input_path.stem

    try:
        karaoke_dir.mkdir(exist_ok=True)
        sep = create_separator(str(karaoke_dir))

        log.info(f"Starting separation for track {track_id}")
        output_files = sep.separate(str(input_path))
        log.info(f"Separation returned: {output_files}")

        stem_files = []
        for f in output_files:
            file_path = Path(f)
            if not file_path.is_absolute():
                file_path = karaoke_dir / file_path.name
            if file_path.exists():
                stem_files.append(file_path)
                log.info(f"Found stem: {file_path}")
            else:
                log.warning(f"Stem file not found: {f} -> {file_path}")

        if not stem_files:
            found_files = list(karaoke_dir.glob(f"*.{OUTPUT_FORMAT}"))
            log.info(f"Fallback: scanning {karaoke_dir}, found: {found_files}")
            stem_files = [f for f in found_files if f.stat().st_mtime >= start_time]

        if not stem_files:
            raise Exception(f"No output files found in {karaoke_dir}")

        vocal_stem = None
        instrumental_stems = []

        for stem in stem_files:
            name_lower = stem.name.lower()
            if "vocals" in name_lower:
                vocal_stem = stem
            elif "instrumental" in name_lower or "no_vocals" in name_lower:
                instrumental_stems = [stem]
                break
            else:
                instrumental_stems.append(stem)

        if not instrumental_stems:
            raise Exception("No instrumental stems found in output")

        instrumental_filename = f"{song_name}_instrumental.{OUTPUT_FORMAT}"
        final_karaoke_path = karaoke_dir / instrumental_filename

        if len(instrumental_stems) == 1:
            if instrumental_stems[0] != final_karaoke_path:
                shutil.move(str(instrumental_stems[0]), str(final_karaoke_path))
        else:
            try:
                cmd = ["ffmpeg", "-y"]
                for stem in instrumental_stems:
                    cmd.extend(["-i", str(stem)])
                filter_complex = f"amix=inputs={len(instrumental_stems)}:normalize=0"
                cmd.extend(["-filter_complex", filter_complex, str(final_karaoke_path)])
                log.info(f"Mixing {len(instrumental_stems)} stems: {cmd}")
                result = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=FFMPEG_TIMEOUT_SECONDS,
                )
                if result.returncode != 0:
                    raise Exception(f"FFmpeg failed: {result.stderr}")
            except subprocess.TimeoutExpired:
                raise Exception(f"FFmpeg timeout after {FFMPEG_TIMEOUT_SECONDS}s")
            finally:
                for stem in instrumental_stems:
                    if stem != final_karaoke_path and stem.exists():
                        stem.unlink(missing_ok=True)

        final_vocal_path = ""
        if vocal_stem:
            vocal_filename = f"{song_name}_vocals.{OUTPUT_FORMAT}"
            vocal_dest = karaoke_dir / vocal_filename
            if vocal_stem != vocal_dest:
                shutil.move(str(vocal_stem), str(vocal_dest))
            final_vocal_path = str(vocal_dest)

        for leftover in karaoke_dir.glob(f"*_{MODEL_NAME}.{OUTPUT_FORMAT}"):
            if leftover.name not in [
                instrumental_filename,
                f"{song_name}_vocals.{OUTPUT_FORMAT}",
            ]:
                leftover.unlink(missing_ok=True)
                log.info(f"Cleaned up leftover stem: {leftover}")

        elapsed_ms = int((time.time() - start_time) * 1000)
        log.info(
            f"Karaoke complete for {track_id}: instrumental={final_karaoke_path}, "
            f"vocals={final_vocal_path}, time={elapsed_ms}ms"
        )

        return SeparationResponse(
            instrumental_path=str(final_karaoke_path),
            vocal_path=final_vocal_path,
            processing_time_ms=elapsed_ms,
        )

    except Exception as e:
        log.exception(f"Separation failed for {track_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ===========================================================================
# Lyrics: text normalization helpers
# ===========================================================================

_TITLE_STRIP_PATTERNS = [
    re.compile(r"\s*\(feat\.?\s+[^)]*\)", re.IGNORECASE),
    re.compile(r"\s*\[feat\.?\s+[^\]]*\]", re.IGNORECASE),
    re.compile(r"\s*ft\.?\s+.*$", re.IGNORECASE),
    re.compile(r"\s*\(with\s+[^)]*\)", re.IGNORECASE),
    re.compile(r"\s*\[with\s+[^\]]*\]", re.IGNORECASE),
    re.compile(r"\s*\(remaster(ed)?\s*\d*\)", re.IGNORECASE),
    re.compile(r"\s*\[remaster(ed)?\s*\d*\]", re.IGNORECASE),
    re.compile(r"\s*-\s*single$", re.IGNORECASE),
    re.compile(r"\s*\(deluxe[^)]*\)", re.IGNORECASE),
    re.compile(r"\s*\(bonus\s*track\)", re.IGNORECASE),
    re.compile(r"\s*\(live[^)]*\)", re.IGNORECASE),
    re.compile(r"\s*\[live[^\]]*\]", re.IGNORECASE),
    re.compile(r"\s*\(acoustic[^)]*\)", re.IGNORECASE),
    re.compile(r"\s*\(remix[^)]*\)", re.IGNORECASE),
    re.compile(r"\s*\[remix[^\]]*\]", re.IGNORECASE),
    re.compile(r"\s*\(radio\s*edit\)", re.IGNORECASE),
    re.compile(r"\s*\(explicit\)", re.IGNORECASE),
    re.compile(r"\s*\(clean\)", re.IGNORECASE),
    re.compile(r"\s*\(original\s*mix\)", re.IGNORECASE),
    re.compile(r"\s*\(extended[^)]*\)", re.IGNORECASE),
]

_ARTIST_SPLIT_RE = re.compile(
    r",\s*|\s+(?:&|and|feat\.?|ft\.?|vs\.?|x|×)\s+",
    re.IGNORECASE,
)


def _normalize_text(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.lower().strip()
    for pat in _TITLE_STRIP_PATTERNS:
        text = pat.sub("", text)
    text = re.sub(r"[^\w\s]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _normalize_artist(artist: str) -> str:
    normalized = _normalize_text(artist)
    normalized = re.sub(r"^the\s+", "", normalized)
    return normalized


def _split_artists(artist: str) -> list[str]:
    parts = _ARTIST_SPLIT_RE.split(artist)
    return [p.strip() for p in parts if p.strip()]


def _strip_all_parentheticals(title: str) -> str:
    title = re.sub(r"\([^)]*\)", "", title)
    title = re.sub(r"\[[^\]]*\]", "", title)
    return re.sub(r"  +", " ", title).strip()


def _generate_query_variations(artist: str, title: str) -> list[tuple[str, str]]:
    seen: set[tuple[str, str]] = set()
    variations: list[tuple[str, str]] = []

    def _add(a: str, t: str):
        key = (a.strip().lower(), t.strip().lower())
        if key not in seen and key[0] and key[1]:
            seen.add(key)
            variations.append((a.strip(), t.strip()))

    _add(artist, title)

    clean_title = title
    for pat in _TITLE_STRIP_PATTERNS:
        clean_title = pat.sub("", clean_title)
    clean_title = clean_title.strip()
    _add(artist, clean_title)

    bare_title = _strip_all_parentheticals(title)
    _add(artist, bare_title)

    norm_artist = _normalize_artist(artist)
    _add(norm_artist, title)

    _add(norm_artist, clean_title)
    _add(norm_artist, bare_title)

    norm_title = _normalize_text(title)
    _add(norm_artist, norm_title)

    artists = _split_artists(artist)
    if len(artists) > 1:
        _add(artists[0], title)
        _add(artists[0], clean_title)
        _add(artists[0], bare_title)

    if " - " in title:
        parts = title.split(" - ", 1)
        _add(artist, parts[-1].strip())
        _add(parts[0].strip(), parts[-1].strip())

    return variations


# ===========================================================================
# Lyrics: LRC parsing & validation helpers
# ===========================================================================

def _extract_lrc_timestamps(lrc_content: str) -> list[float]:
    timestamps = []
    for line in lrc_content.split("\n"):
        match = re.match(r"^\[(\d+):(\d{2})(?:\.(\d{2,3}))?\]", line.strip())
        if match:
            mins = int(match.group(1))
            secs = int(match.group(2))
            cs = match.group(3)
            ms = int(cs.ljust(3, "0")[:3]) / 1000 if cs else 0
            timestamps.append(mins * 60 + secs + ms)
    return timestamps


def _has_lrc_timestamps(content: str) -> bool:
    return bool(re.search(r"^\[\d+:\d{2}", content, re.MULTILINE))


def _count_lyric_lines(content: str) -> int:
    count = 0
    for line in content.split("\n"):
        stripped = line.strip()
        if not stripped:
            continue
        if re.match(r"^\[\d+:\d{2}", stripped):
            text = re.sub(r"^\[\d+:\d{2}(?:\.\d{2,3})?\]\s*", "", stripped)
            if text:
                count += 1
        elif not stripped.startswith("["):
            count += 1
    return count


def _validate_lyrics(
    content: str,
    duration_seconds: float | None,
    artist: str,
    title: str,
) -> bool:
    line_count = _count_lyric_lines(content)
    if line_count < 2:
        log.info(f"Rejected lyrics for '{artist} - {title}': too few lines ({line_count})")
        return False
    if line_count > 2000:
        log.info(f"Rejected lyrics for '{artist} - {title}': too many lines ({line_count})")
        return False

    if "<html" in content.lower() or "<body" in content.lower():
        log.info(f"Rejected lyrics for '{artist} - {title}': contains HTML")
        return False

    if duration_seconds and _has_lrc_timestamps(content):
        timestamps = _extract_lrc_timestamps(content)
        if timestamps:
            last_ts = max(timestamps)
            if last_ts > duration_seconds + 30:
                log.info(
                    f"Rejected lyrics for '{artist} - {title}': "
                    f"last timestamp {last_ts:.0f}s exceeds duration {duration_seconds:.0f}s"
                )
                return False
            if last_ts < duration_seconds * 0.15:
                log.info(
                    f"Rejected lyrics for '{artist} - {title}': "
                    f"last timestamp {last_ts:.0f}s covers <15% of duration {duration_seconds:.0f}s"
                )
                return False

    return True


def _strip_lrc_to_plain(content: str) -> str:
    lines = []
    for line in content.split("\n"):
        stripped = line.strip()
        if not stripped:
            continue
        cleaned = re.sub(r"\[\d+:\d{2}(?:\.\d{2,3})?\]\s*", "", stripped)
        if cleaned and not re.match(r"^\[[a-z]{2}:.+\]$", cleaned, re.IGNORECASE):
            lines.append(cleaned.lower().strip())
    return "\n".join(lines)


def _lyrics_similarity(a: str, b: str) -> float:
    plain_a = _strip_lrc_to_plain(a)
    plain_b = _strip_lrc_to_plain(b)
    if not plain_a or not plain_b:
        return 0.0

    lines_a = set(plain_a.split("\n"))
    lines_b = set(plain_b.split("\n"))

    if not lines_a or not lines_b:
        return 0.0

    intersection = lines_a & lines_b
    union = lines_a | lines_b
    return len(intersection) / len(union)


# ===========================================================================
# Lyrics: negative cache
# ===========================================================================

def _is_negative_cache_valid(path: Path) -> bool:
    if not path.exists():
        return False
    try:
        content = path.read_text(encoding="utf-8").strip()
        if content != NEGATIVE_CACHE_MARKER:
            return False
        age_days = (time.time() - path.stat().st_mtime) / 86400
        return age_days < NEGATIVE_CACHE_MAX_AGE_DAYS
    except Exception:
        return False


def _write_negative_cache(path: Path):
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(NEGATIVE_CACHE_MARKER, encoding="utf-8")
    except Exception as e:
        log.warning(f"Failed to write negative cache to {path}: {e}")


# ===========================================================================
# Lyrics: source fetchers
# ===========================================================================

def _fuzzy_match_artist_title(
    result_artist: str,
    result_title: str,
    query_artist: str,
    query_title: str,
) -> bool:
    ra = _normalize_artist(result_artist)
    rt = _normalize_text(result_title)
    qa = _normalize_artist(query_artist)
    qt = _normalize_text(query_title)

    qt_words = set(qt.split())
    rt_words = set(rt.split())
    if not qt_words:
        return False
    title_overlap = len(qt_words & rt_words) / len(qt_words)
    if title_overlap < 0.5:
        return False

    qa_words = set(qa.split())
    ra_words = set(ra.split())
    artist_overlap = len(qa_words & ra_words)
    if artist_overlap == 0 and qa not in ra and ra not in qa:
        return False

    return True


def _fetch_from_lrclib(
    artist: str,
    title: str,
    album: str | None,
    duration_seconds: float | None,
) -> tuple[str | None, str]:
    headers = {"User-Agent": LRCLIB_USER_AGENT}

    params: dict[str, str] = {"artist_name": artist, "track_name": title}
    if album:
        params["album_name"] = album
    if duration_seconds:
        params["duration"] = str(int(duration_seconds))

    try:
        resp = _http_session.get(
            f"{LRCLIB_BASE_URL}/get",
            params=params,
            headers=headers,
            timeout=LRCLIB_TIMEOUT_SECONDS,
        )
        if resp.status_code == 200:
            data = resp.json()
            if data.get("instrumental"):
                log.info(f"LRCLIB: '{artist} - {title}' marked as instrumental")
                return None, "instrumental"
            synced = data.get("syncedLyrics")
            if synced:
                return synced, "lrclib-synced"
            plain = data.get("plainLyrics")
            if plain:
                return plain, "lrclib-plain"
        elif resp.status_code != 404:
            log.warning(f"LRCLIB /get returned {resp.status_code} for '{artist} - {title}'")
    except Exception as e:
        log.warning(f"LRCLIB /get failed for '{artist} - {title}': {e}")

    try:
        search_params: dict[str, str] = {"track_name": title, "artist_name": artist}
        resp = _http_session.get(
            f"{LRCLIB_BASE_URL}/search",
            params=search_params,
            headers=headers,
            timeout=LRCLIB_TIMEOUT_SECONDS,
        )
        if resp.status_code == 200:
            results = resp.json()
            if not results:
                return None, "not_found"

            best_synced = None
            best_plain = None
            for entry in results:
                if entry.get("instrumental"):
                    continue

                entry_artist = entry.get("artistName", "")
                entry_title = entry.get("trackName", "")
                if not _fuzzy_match_artist_title(entry_artist, entry_title, artist, title):
                    log.debug(
                        f"LRCLIB search: skipping '{entry_artist} - {entry_title}' "
                        f"(no match for '{artist} - {title}')"
                    )
                    continue

                if duration_seconds:
                    entry_dur = entry.get("duration", 0)
                    if entry_dur and abs(entry_dur - duration_seconds) > 15:
                        continue

                if entry.get("syncedLyrics") and not best_synced:
                    best_synced = entry["syncedLyrics"]
                if entry.get("plainLyrics") and not best_plain:
                    best_plain = entry["plainLyrics"]

                if best_synced:
                    break

            if best_synced:
                return best_synced, "lrclib-search-synced"
            if best_plain:
                return best_plain, "lrclib-search-plain"
    except Exception as e:
        log.warning(f"LRCLIB /search failed for '{artist} - {title}': {e}")

    return None, "not_found"


# ===========================================================================
# Lyrics: orchestrator with multi-variation search
# ===========================================================================

def _search_all_sources(
    _artist: str,
    _title: str,
    album: str | None,
    duration_seconds: float | None,
    variations: list[tuple[str, str]],
) -> tuple[list[tuple[str, str, bool]], bool]:
    candidates: list[tuple[str, str, bool]] = []
    seen_plain_hashes: set[int] = set()

    def _try_add(lyrics: str | None, source: str, src_artist: str, src_title: str) -> bool:
        if source == "instrumental":
            return True
        if not lyrics:
            return False
        if not _validate_lyrics(lyrics, duration_seconds, src_artist, src_title):
            return False
        plain_hash = hash(_strip_lrc_to_plain(lyrics))
        if plain_hash in seen_plain_hashes:
            return False
        seen_plain_hashes.add(plain_hash)
        is_synced = _has_lrc_timestamps(lyrics)
        candidates.append((lyrics, source, is_synced))
        log.info(
            f"Candidate [{source}] query='{src_artist} - {src_title}': "
            f"{'synced' if is_synced else 'plain'}, {_count_lyric_lines(lyrics)} lines"
        )
        return False

    for var_artist, var_title in variations:
        lyrics, source = _fetch_from_lrclib(var_artist, var_title, album, duration_seconds)
        is_instrumental = _try_add(lyrics, source, var_artist, var_title)
        if is_instrumental:
            return [], True
        album = None
        if len(candidates) >= 2:
            break

    return candidates, False


def _select_best_candidate(
    candidates: list[tuple[str, str, bool]],
) -> tuple[str, str] | None:
    if not candidates:
        return None

    if len(candidates) >= 2:
        verified_synced: tuple[str, str] | None = None
        verified_plain: tuple[str, str] | None = None

        for i, (lyrics_a, source_a, synced_a) in enumerate(candidates):
            for j, (lyrics_b, source_b, synced_b) in enumerate(candidates):
                if i >= j:
                    continue
                sim = _lyrics_similarity(lyrics_a, lyrics_b)
                log.info(f"Cross-validation {source_a} vs {source_b}: similarity={sim:.2f}")
                if sim >= 0.35:
                    if not verified_synced:
                        if synced_a:
                            verified_synced = (lyrics_a, source_a)
                        elif synced_b:
                            verified_synced = (lyrics_b, source_b)
                    if not verified_plain:
                        verified_plain = (lyrics_a, source_a)

        if verified_synced:
            log.info(f"Cross-validated synced lyrics from {verified_synced[1]}")
            return verified_synced
        if verified_plain:
            log.info(f"Cross-validated plain lyrics from {verified_plain[1]}")
            return verified_plain

    synced = [(l, s) for l, s, sync in candidates if sync]
    if synced:
        log.info(f"Using best single-source synced lyrics from {synced[0][1]}")
        return synced[0]

    log.info(f"Using best single-source plain lyrics from {candidates[0][1]}")
    return candidates[0][0], candidates[0][1]


# ===========================================================================
# Lyrics: API endpoint
# ===========================================================================

@app.post("/api/fetch-lyrics", response_model=LyricsResponse)
def fetch_lyrics(request: LyricsRequest):
    output_path = _validate_media_path(request.outputPath)

    if request.force and output_path.exists():
        log.info(f"Force mode: deleting existing lyrics file {output_path}")
        output_path.unlink(missing_ok=True)

    if not request.force and output_path.exists() and output_path.stat().st_size > 0:
        content = output_path.read_text(encoding="utf-8").strip()
        if content != NEGATIVE_CACHE_MARKER:
            log.info(f"Lyrics already exist: {output_path}")
            return LyricsResponse(
                success=True,
                outputPath=str(output_path),
                source="cached",
                synced=_has_lrc_timestamps(content),
                lyrics=content,
            )

    if not request.force and _is_negative_cache_valid(output_path):
        log.info(f"Negative cache hit for: {request.artist} - {request.title}")
        return LyricsResponse(success=False, source="negative-cache")

    artist = request.artist
    title = request.title
    album = request.album
    duration_seconds = request.durationMs / 1000.0 if request.durationMs else None

    log.info(
        f"Searching lyrics for: '{artist} - {title}'"
        + (f" album='{album}'" if album else "")
        + (f" duration={duration_seconds:.0f}s" if duration_seconds else "")
    )

    variations = _generate_query_variations(artist, title)
    log.info(f"Generated {len(variations)} query variations for '{artist} - {title}'")

    candidates, is_instrumental = _search_all_sources(artist, title, album, duration_seconds, variations)

    if is_instrumental:
        log.info(f"Track '{artist} - {title}' is instrumental, skipping lyrics")
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="instrumental")

    if not candidates:
        log.info(f"No lyrics found for: '{artist} - {title}' from any source")
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="exhausted")

    result = _select_best_candidate(candidates)

    if not result:
        log.info(f"No valid candidate selected for: '{artist} - {title}'")
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="exhausted")

    chosen_lyrics, chosen_source = result

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(chosen_lyrics, encoding="utf-8")
    is_synced = _has_lrc_timestamps(chosen_lyrics)
    log.info(
        f"Wrote {'synced' if is_synced else 'plain'} lyrics to: {output_path} "
        f"(source: {chosen_source}, candidates: {len(candidates)})"
    )

    return LyricsResponse(
        success=True,
        outputPath=str(output_path),
        source=chosen_source,
        synced=is_synced,
        lyrics=chosen_lyrics,
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
