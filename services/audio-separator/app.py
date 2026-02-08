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
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "htdemucs")
OUTPUT_FORMAT = os.getenv("OUTPUT_FORMAT", "wav")
USE_CUDA = os.getenv("DEVICE", "cuda") == "cuda"
ALLOWED_MEDIA_ROOT = Path(os.getenv("MEDIA_PATH", "/media")).resolve()
FFMPEG_TIMEOUT_SECONDS = 300

LRCLIB_BASE_URL = "https://lrclib.net/api"
LRCLIB_USER_AGENT = "yay-tsa/1.0.0 (https://yay-tsa.com)"
LRCLIB_TIMEOUT_SECONDS = 10
NEGATIVE_CACHE_MARKER = "[no lyrics found]"
NEGATIVE_CACHE_MAX_AGE_DAYS = 30


@asynccontextmanager
async def lifespan(app: FastAPI):
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


class LyricsResponse(BaseModel):
    success: bool
    outputPath: str | None = None
    source: str | None = None
    synced: bool = False


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
    input_path = Path(request.inputPath).resolve()
    track_id = request.trackId

    try:
        input_path.relative_to(ALLOWED_MEDIA_ROOT)
    except ValueError:
        log.error(f"Path traversal attempt: {input_path}")
        raise HTTPException(status_code=403, detail="Invalid input path")

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
                    timeout=FFMPEG_TIMEOUT_SECONDS
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
            if leftover.name not in [instrumental_filename, f"{song_name}_vocals.{OUTPUT_FORMAT}"]:
                leftover.unlink(missing_ok=True)
                log.info(f"Cleaned up leftover stem: {leftover}")

        elapsed_ms = int((time.time() - start_time) * 1000)
        log.info(f"Karaoke complete for {track_id}: instrumental={final_karaoke_path}, vocals={final_vocal_path}, time={elapsed_ms}ms")

        return SeparationResponse(
            instrumental_path=str(final_karaoke_path),
            vocal_path=final_vocal_path,
            processing_time_ms=elapsed_ms,
        )

    except Exception as e:
        log.exception(f"Separation failed for {track_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


def _normalize_text(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.lower().strip()
    text = re.sub(r"\s*\(feat\.?\s+[^)]*\)", "", text)
    text = re.sub(r"\s*\[feat\.?\s+[^\]]*\]", "", text)
    text = re.sub(r"\s*ft\.?\s+.*$", "", text)
    text = re.sub(r"\s*\(remaster(ed)?\s*\d*\)", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*\[remaster(ed)?\s*\d*\]", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*-\s*single$", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*\(deluxe[^)]*\)", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s*\(bonus track\)", "", text, flags=re.IGNORECASE)
    text = re.sub(r"[^\w\s]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _normalize_artist(artist: str) -> str:
    normalized = _normalize_text(artist)
    normalized = re.sub(r"^the\s+", "", normalized)
    return normalized


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


def _validate_lyrics(content: str, duration_seconds: float | None, artist: str, title: str) -> bool:
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
            if last_ts > duration_seconds + 10:
                log.info(
                    f"Rejected lyrics for '{artist} - {title}': "
                    f"last timestamp {last_ts:.0f}s exceeds duration {duration_seconds:.0f}s"
                )
                return False
            if last_ts < duration_seconds * 0.2:
                log.info(
                    f"Rejected lyrics for '{artist} - {title}': "
                    f"last timestamp {last_ts:.0f}s covers <20% of duration {duration_seconds:.0f}s"
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


def _fetch_from_lrclib(artist: str, title: str, album: str | None, duration_seconds: float | None) -> tuple[str | None, str]:
    headers = {"User-Agent": LRCLIB_USER_AGENT}

    params: dict[str, str] = {"artist_name": artist, "track_name": title}
    if album:
        params["album_name"] = album
    if duration_seconds:
        params["duration"] = str(int(duration_seconds))

    try:
        resp = http_requests.get(
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
        resp = http_requests.get(
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
                if duration_seconds:
                    entry_dur = entry.get("duration", 0)
                    if abs(entry_dur - duration_seconds) > 10:
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


def _fetch_from_syncedlyrics(artist: str, title: str) -> tuple[str | None, str]:
    try:
        import syncedlyrics

        search_term = f"{artist} {title}"
        lrc = syncedlyrics.search(search_term, providers=["Musixmatch", "NetEase", "Megalobiz"])
        if lrc:
            return lrc, "syncedlyrics"

        lrc = syncedlyrics.search(search_term, synced_only=False, providers=["Genius"])
        if lrc:
            return lrc, "genius-plain"

    except Exception as e:
        log.warning(f"syncedlyrics search failed for '{artist} - {title}': {e}")

    return None, "not_found"


def _fetch_from_lrclib_normalized(artist: str, title: str, duration_seconds: float | None) -> tuple[str | None, str]:
    norm_artist = _normalize_artist(artist)
    norm_title = _normalize_text(title)

    if norm_artist == artist.lower().strip() and norm_title == title.lower().strip():
        return None, "not_found"

    return _fetch_from_lrclib(norm_artist, norm_title, None, duration_seconds)


@app.post("/api/fetch-lyrics", response_model=LyricsResponse)
def fetch_lyrics(request: LyricsRequest):
    output_path = Path(request.outputPath).resolve()

    try:
        output_path.relative_to(ALLOWED_MEDIA_ROOT)
    except ValueError:
        log.error(f"Path traversal attempt: {output_path}")
        raise HTTPException(status_code=403, detail="Invalid output path")

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

    candidates: list[tuple[str, str, bool]] = []

    sources = [
        ("LRCLIB", lambda: _fetch_from_lrclib(artist, title, album, duration_seconds)),
        ("syncedlyrics", lambda: _fetch_from_syncedlyrics(artist, title)),
        ("LRCLIB-normalized", lambda: _fetch_from_lrclib_normalized(artist, title, duration_seconds)),
    ]

    for source_name, fetch_fn in sources:
        try:
            lyrics, source = fetch_fn()
        except Exception as e:
            log.warning(f"{source_name} failed for '{artist} - {title}': {e}")
            continue

        if source == "instrumental":
            log.info(f"Track '{artist} - {title}' is instrumental, skipping lyrics")
            _write_negative_cache(output_path)
            return LyricsResponse(success=False, source="instrumental")

        if not lyrics:
            continue

        if not _validate_lyrics(lyrics, duration_seconds, artist, title):
            continue

        is_synced = _has_lrc_timestamps(lyrics)
        candidates.append((lyrics, source, is_synced))
        log.info(f"Candidate from {source_name} ({source}): {'synced' if is_synced else 'plain'}, {_count_lyric_lines(lyrics)} lines")

    if not candidates:
        log.info(f"No lyrics found for: '{artist} - {title}' from any source")
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="exhausted")

    chosen_lyrics = None
    chosen_source = None

    if len(candidates) >= 2:
        verified_synced = None
        verified_plain = None

        for i, (lyrics_a, source_a, synced_a) in enumerate(candidates):
            for j, (lyrics_b, source_b, _synced_b) in enumerate(candidates):
                if i >= j:
                    continue
                sim = _lyrics_similarity(lyrics_a, lyrics_b)
                log.info(f"Cross-validation {source_a} vs {source_b}: similarity={sim:.2f}")
                if sim >= 0.4:
                    if synced_a and not verified_synced:
                        verified_synced = (lyrics_a, source_a)
                    if not verified_plain:
                        verified_plain = (lyrics_a, source_a)

        for lyrics_c, source_c, synced_c in candidates:
            if synced_c:
                for lyrics_d, source_d, _synced_d in candidates:
                    if source_c == source_d:
                        continue
                    sim = _lyrics_similarity(lyrics_c, lyrics_d)
                    if sim >= 0.4 and not verified_synced:
                        verified_synced = (lyrics_c, source_c)

        if verified_synced:
            chosen_lyrics, chosen_source = verified_synced
            log.info(f"Cross-validated synced lyrics from {chosen_source}")
        elif verified_plain:
            chosen_lyrics, chosen_source = verified_plain
            log.info(f"Cross-validated plain lyrics from {chosen_source}")

    if not chosen_lyrics:
        synced_candidates = [(l, s) for l, s, sync in candidates if sync]
        if synced_candidates:
            chosen_lyrics, chosen_source = synced_candidates[0]
        else:
            chosen_lyrics, chosen_source = candidates[0][0], candidates[0][1]
        log.info(f"Using best single-source lyrics from {chosen_source}")

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
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
