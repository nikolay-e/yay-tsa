import os
import re
import json
import time
import base64
import threading
import logging
import shutil
import unicodedata
from html import unescape as _html_unescape
from pathlib import Path
from contextlib import asynccontextmanager
from urllib.parse import unquote as _url_unquote, urlparse as _urlparse

import requests as http_requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# Force torchaudio to use soundfile backend (compatible with all platforms and audio formats)
os.environ.setdefault("TORCHAUDIO_USE_BACKEND_DISPATCHER", "0")
import torchaudio
torchaudio.set_audio_backend("soundfile")

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "htdemucs")
OUTPUT_FORMAT = os.getenv("OUTPUT_FORMAT", "wav")
USE_CUDA = os.getenv("DEVICE", "cpu") == "cuda"
FFMPEG_TIMEOUT_SECONDS = 300

# Support multiple allowed media roots (comma-separated).
# MEDIA_PATHS takes precedence over MEDIA_PATH for multi-root setups.
_raw_media_paths = os.getenv("MEDIA_PATHS", os.getenv("MEDIA_PATH", "/media"))
ALLOWED_MEDIA_ROOTS: list[Path] = [
    Path(p.strip()).resolve()
    for p in _raw_media_paths.split(",")
    if p.strip()
]

LRCLIB_BASE_URL = "https://lrclib.net/api"
LRCLIB_USER_AGENT = "yay-tsa/1.0.0 (https://yay-tsa.com)"
LRCLIB_TIMEOUT_SECONDS = 10
NEGATIVE_CACHE_MARKER = "[no lyrics found]"
NEGATIVE_CACHE_MAX_AGE_DAYS = 30

QQMUSIC_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
QQMUSIC_LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
QQMUSIC_TIMEOUT_SECONDS = 10
QQMUSIC_REFERER = "https://y.qq.com/portal/player.html"

DDG_HTML_URL = "https://html.duckduckgo.com/html/"
DDG_TIMEOUT_SECONDS = 15
WEB_FETCH_TIMEOUT_SECONDS = 12
WEB_SEARCH_MAX_PAGES = 5
WEB_MAX_RESPONSE_BYTES = 512 * 1024
WEB_SEARCH_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)


# ---------------------------------------------------------------------------
# Path security
# ---------------------------------------------------------------------------

def _is_path_allowed(path: Path) -> bool:
    """Return True if path is inside one of the allowed media roots."""
    for root in ALLOWED_MEDIA_ROOTS:
        try:
            path.relative_to(root)
            return True
        except ValueError:
            continue
    return False


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
    log.info(
        "Audio Separator Service starting — model=%s format=%s device=%s",
        MODEL_NAME, OUTPUT_FORMAT, "cuda" if USE_CUDA else "cpu",
    )
    log.info("Allowed media roots: %s", [str(r) for r in ALLOWED_MEDIA_ROOTS])
    log.info("Model will be loaded on first separation request")
    yield


app = FastAPI(title="Audio Separator Service", version="3.0.0", lifespan=lifespan)

_separator_lock = threading.Lock()


# ---------------------------------------------------------------------------
# Audio separation — demucs Python API (no external audio-separator package)
# ---------------------------------------------------------------------------

def _separate_with_demucs(input_path: str, output_dir: str) -> list[str]:
    """Separate audio into stems using the demucs Python API directly."""
    import torch
    from demucs.pretrained import get_model
    from demucs.apply import apply_model
    from demucs.audio import AudioFile

    log.info("Loading demucs model %s…", MODEL_NAME)
    model = get_model(MODEL_NAME)
    device = "cuda" if USE_CUDA else "cpu"
    model.to(device)

    log.info("Reading audio: %s", input_path)
    wav = AudioFile(input_path).read(
        streams=0,
        samplerate=model.samplerate,
        channels=model.audio_channels,
    )

    log.info("Applying separation model…")
    ref = wav.mean(0)
    wav = (wav - ref.mean()) / ref.std()

    with torch.no_grad():
        sources = apply_model(model, wav[None], device=device)[0]

    sources = sources * ref.std() + ref.mean()

    if sources.shape[0] < 2:
        raise RuntimeError(f"Expected at least 2 stems, got {sources.shape[0]}")

    track_name = Path(input_path).stem
    stems_dir = Path(output_dir) / MODEL_NAME / track_name
    stems_dir.mkdir(parents=True, exist_ok=True)

    vocal_file = stems_dir / f"vocals.{OUTPUT_FORMAT}"
    instrumental_file = stems_dir / f"no_vocals.{OUTPUT_FORMAT}"

    log.info("Saving %d stems to %s…", sources.shape[0], stems_dir)
    # demucs htdemucs two-stems: index 0 = accompaniment, index 1 = vocals
    torchaudio.save(str(instrumental_file), sources[0], model.samplerate)
    torchaudio.save(str(vocal_file), sources[1], model.samplerate)

    log.info("Stems saved — instrumental=%s vocals=%s", instrumental_file, vocal_file)
    return [str(vocal_file), str(instrumental_file)]


# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------

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
    lyrics: str | None = None


class HealthResponse(BaseModel):
    status: str
    model: str
    device: str


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

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

    if not _is_path_allowed(input_path):
        log.error("Path not in allowed roots: %s", input_path)
        raise HTTPException(status_code=403, detail="Invalid input path")

    if not input_path.exists():
        raise HTTPException(status_code=404, detail=f"Input file not found: {input_path}")

    karaoke_dir = input_path.parent / ".karaoke"
    karaoke_path = karaoke_dir / f"{input_path.stem}_instrumental.{OUTPUT_FORMAT}"
    vocal_path = karaoke_dir / f"{input_path.stem}_vocals.{OUTPUT_FORMAT}"

    if karaoke_path.exists():
        log.info("Karaoke file already exists, returning cached: %s", karaoke_path)
        return SeparationResponse(
            instrumental_path=str(karaoke_path),
            vocal_path=str(vocal_path) if vocal_path.exists() else "",
            processing_time_ms=0,
        )

    start_time = time.time()

    try:
        karaoke_dir.mkdir(exist_ok=True)
        temp_output = karaoke_dir / "temp"
        temp_output.mkdir(exist_ok=True)

        with _separator_lock:
            log.info("Starting separation for track %s", track_id)
            output_files = _separate_with_demucs(str(input_path), str(temp_output))

        for f in output_files:
            file_path = Path(f)
            final_path = vocal_path if file_path.stem == "vocals" else karaoke_path
            if file_path.exists():
                shutil.move(str(file_path), str(final_path))
                log.info("Moved %s → %s", file_path.name, final_path)

        shutil.rmtree(str(temp_output), ignore_errors=True)

        if not karaoke_path.exists():
            raise RuntimeError(f"Instrumental file missing after separation: {karaoke_path}")

        elapsed_ms = int((time.time() - start_time) * 1000)
        log.info(
            "Separation complete for %s: %dms, instrumental=%s vocals=%s",
            track_id, elapsed_ms, karaoke_path, vocal_path if vocal_path.exists() else "none",
        )

        return SeparationResponse(
            instrumental_path=str(karaoke_path),
            vocal_path=str(vocal_path) if vocal_path.exists() else "",
            processing_time_ms=elapsed_ms,
        )

    except Exception as e:
        log.exception("Separation failed for track %s", track_id)
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
    r"\s*(?:,\s*|\s+(?:&|and|feat\.?|ft\.?|vs\.?|x|×)\s+)\s*",
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
    title = re.sub(r"\s*\([^)]*\)", "", title)
    title = re.sub(r"\s*\[[^\]]*\]", "", title)
    return title.strip()


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
        log.info("Rejected lyrics for '%s - %s': too few lines (%d)", artist, title, line_count)
        return False
    if line_count > 2000:
        log.info("Rejected lyrics for '%s - %s': too many lines (%d)", artist, title, line_count)
        return False

    if "<html" in content.lower() or "<body" in content.lower():
        log.info("Rejected lyrics for '%s - %s': contains HTML", artist, title)
        return False

    if duration_seconds and _has_lrc_timestamps(content):
        timestamps = _extract_lrc_timestamps(content)
        if timestamps:
            last_ts = max(timestamps)
            if last_ts > duration_seconds + 30:
                log.info(
                    "Rejected lyrics for '%s - %s': last timestamp %ds exceeds duration %ds",
                    artist, title, int(last_ts), int(duration_seconds),
                )
                return False
            if last_ts < duration_seconds * 0.15:
                log.info(
                    "Rejected lyrics for '%s - %s': last timestamp %ds covers <15%% of duration %ds",
                    artist, title, int(last_ts), int(duration_seconds),
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
        log.warning("Failed to write negative cache to %s: %s", path, e)


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
                log.info("LRCLIB: '%s - %s' marked as instrumental", artist, title)
                return None, "instrumental"
            synced = data.get("syncedLyrics")
            if synced:
                return synced, "lrclib-synced"
            plain = data.get("plainLyrics")
            if plain:
                return plain, "lrclib-plain"
        elif resp.status_code != 404:
            log.warning("LRCLIB /get returned %d for '%s - %s'", resp.status_code, artist, title)
    except Exception as e:
        log.warning("LRCLIB /get failed for '%s - %s': %s", artist, title, e)

    try:
        resp = _http_session.get(
            f"{LRCLIB_BASE_URL}/search",
            params={"track_name": title, "artist_name": artist},
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
        log.warning("LRCLIB /search failed for '%s - %s': %s", artist, title, e)

    return None, "not_found"


def _fetch_from_syncedlyrics(artist: str, title: str) -> tuple[str | None, str]:
    try:
        import syncedlyrics

        lrc = syncedlyrics.search(
            f"{artist} {title}",
            providers=["Musixmatch", "NetEase", "Megalobiz", "Lrclib"],
        )
        if lrc:
            return lrc, "syncedlyrics-synced"
    except ImportError:
        log.warning("syncedlyrics package not installed, skipping")
    except Exception as e:
        log.warning("syncedlyrics search failed for '%s - %s': %s", artist, title, e)

    return None, "not_found"


def _fetch_from_qqmusic(
    artist: str,
    title: str,
    duration_seconds: float | None,
) -> tuple[str | None, str]:
    search_query = f"{artist} {title}"
    headers = {"User-Agent": LRCLIB_USER_AGENT, "Referer": QQMUSIC_REFERER}

    try:
        resp = _http_session.get(
            QQMUSIC_SEARCH_URL,
            params={"format": "json", "p": "1", "n": "5", "w": search_query, "cr": "1", "t": "0"},
            headers={"User-Agent": LRCLIB_USER_AGENT},
            timeout=QQMUSIC_TIMEOUT_SECONDS,
        )
        if resp.status_code != 200:
            return None, "not_found"

        song_list = resp.json().get("data", {}).get("song", {}).get("list", [])
        if not song_list:
            return None, "not_found"

        songmid = None
        for song in song_list:
            song_name = song.get("songname", "")
            singer_str = " ".join(s.get("name", "") for s in song.get("singer", []))
            if not _fuzzy_match_artist_title(singer_str, song_name, artist, title):
                continue
            if duration_seconds:
                song_dur = song.get("interval", 0)
                if song_dur and abs(song_dur - duration_seconds) > 15:
                    continue
            songmid = song.get("songmid")
            if songmid:
                log.info("QQMusic: matched '%s - %s' (mid=%s)", singer_str, song_name, songmid)
                break

        if not songmid:
            return None, "not_found"

    except Exception as e:
        log.warning("QQMusic search failed for '%s': %s", search_query, e)
        return None, "not_found"

    try:
        resp = _http_session.get(
            QQMUSIC_LYRIC_URL,
            params={"songmid": songmid, "format": "json", "nobase64": "0"},
            headers=headers,
            timeout=QQMUSIC_TIMEOUT_SECONDS,
        )
        if resp.status_code != 200:
            return None, "not_found"

        text = resp.text.strip()
        if text.startswith("MusicJsonCallback") or text.startswith("callback"):
            text = text[text.index("(") + 1 : text.rindex(")")]

        lyric_data = json.loads(text)
        if lyric_data.get("retcode", -1) != 0:
            return None, "not_found"

        lyric_b64 = lyric_data.get("lyric", "")
        if not lyric_b64:
            return None, "not_found"

        lrc_content = base64.b64decode(lyric_b64).decode("utf-8", errors="replace").strip()
        if not lrc_content or len(lrc_content) < 20:
            return None, "not_found"

        return (lrc_content, "qqmusic-synced") if _has_lrc_timestamps(lrc_content) else (lrc_content, "qqmusic-plain")

    except Exception as e:
        log.warning("QQMusic lyrics fetch failed for mid=%s: %s", songmid, e)
        return None, "not_found"


# ===========================================================================
# Lyrics: web search fallback (DuckDuckGo + generic extraction)
# ===========================================================================

_LYRICS_TAG_PATTERNS = [
    re.compile(r"<div[^>]*\bclass=\"ltf\"[^>]*>", re.IGNORECASE),
    re.compile(r"<div[^>]*data-lyrics-container=\"true\"[^>]*>"),
    re.compile(r"<div[^>]*\bid=\"song-body\"[^>]*>", re.IGNORECASE),
    re.compile(
        r"<div[^>]*\bclass=\"[^\"]*"
        r"(?:lyrics-body|lyric-body|song-text|songtext|lyrics_text|text-lyrics)"
        r"[^\"]*\"[^>]*>",
        re.IGNORECASE,
    ),
    re.compile(r"<div[^>]*\bclass=\"[^\"]*lyrics[^\"]*\"[^>]*>", re.IGNORECASE),
    re.compile(r"<[^>]*\bid=\"songLyricsDiv\"[^>]*>", re.IGNORECASE),
    re.compile(r"<div[^>]*\bid=\"[^\"]*(?:lyrics|song.?text)[^\"]*\"[^>]*>", re.IGNORECASE),
]

_TRUSTED_LYRICS_DOMAINS = {
    "lyricstranslate.com", "genius.com", "azlyrics.com", "songlyrics.com",
    "letras.mus.br", "lyrics.com", "altwall.net", "teksty-pesenok.ru",
    "teksti-pesen.com", "pesni.guru", "tekstipesen.com", "amalgama-lab.com",
    "911pesni.pro", "pesni-accordy.ru", "megalyrics.ru",
}

_SKIP_DOMAINS = {
    "youtube.com", "youtu.be", "vk.com", "facebook.com", "twitter.com", "x.com",
    "instagram.com", "tiktok.com", "wikipedia.org", "reddit.com", "spotify.com",
    "apple.com", "amazon.com", "deezer.com", "soundcloud.com", "music.yandex.ru",
}

_CODE_INDICATORS = ["function ", "var ", "const ", "document.", "window.", "gtag(", "fetch("]


def _has_cyrillic(text: str) -> bool:
    return bool(re.search(r"[\u0400-\u04FF]", text))


def _clean_html_to_text(raw_html: str) -> str:
    text = re.sub(r"<br\s*/?>", "\n", raw_html)
    text = re.sub(r"</(?:div|p|li)>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = _html_unescape(text)
    text = re.sub(r"[ \t]+", " ", text)
    lines = [line.strip() for line in text.split("\n") if line.strip()]
    return "\n".join(lines)


def _extract_tag_content(html: str, open_tag_re: re.Pattern[str]) -> str | None:
    match = open_tag_re.search(html)
    if not match:
        return None
    tag_match = re.match(r"<(\w+)", match.group(0))
    if not tag_match:
        return None
    tag = tag_match.group(1)
    content_start = match.end()
    close_str = f"</{tag}>"
    open_re = re.compile(f"<{tag}\\b")
    depth = 1
    pos = content_start
    while depth > 0 and pos < len(html):
        next_open = open_re.search(html, pos)
        close_pos = html.find(close_str, pos)
        if close_pos < 0:
            break
        next_open_pos = next_open.start() if next_open else len(html)
        if next_open_pos < close_pos:
            depth += 1
            pos = next_open_pos + len(tag) + 1
        else:
            depth -= 1
            if depth == 0:
                return html[content_start:close_pos]
            pos = close_pos + len(close_str)
    return None


def _looks_like_lyrics(text: str) -> bool:
    if not text or len(text) < 50:
        return False
    lines = [line for line in text.split("\n") if line.strip()]
    if len(lines) < 3:
        return False
    code_count = sum(1 for ind in _CODE_INDICATORS if ind in text[:500])
    return code_count < 2


def _extract_lyrics_from_html(html: str) -> str | None:
    for pattern in _LYRICS_TAG_PATTERNS:
        content = _extract_tag_content(html, pattern)
        if not content:
            continue
        text = _clean_html_to_text(content)
        if _looks_like_lyrics(text):
            return text
    return None


def _get_domain(url: str) -> str:
    try:
        return _urlparse(url).netloc.lower().replace("www.", "")
    except Exception:
        return ""


def _ddg_search(query: str, max_results: int = 10) -> list[str]:
    try:
        resp = _http_session.get(
            DDG_HTML_URL,
            params={"q": query},
            headers={"User-Agent": WEB_SEARCH_USER_AGENT},
            timeout=DDG_TIMEOUT_SECONDS,
        )
        if resp.status_code != 200:
            return []
        urls: list[str] = []
        for match in re.finditer(r"uddg=([^&\"]+)", resp.text):
            raw = match.group(1).replace("&amp;", "&")
            url = _url_unquote(raw)
            domain = _get_domain(url)
            if any(domain == skip or domain.endswith("." + skip) for skip in _SKIP_DOMAINS):
                continue
            urls.append(url)
            if len(urls) >= max_results:
                break
        return urls
    except Exception as e:
        log.warning("DuckDuckGo search failed for '%s': %s", query, e)
        return []


def _fetch_from_web_search(
    artist: str,
    title: str,
    duration_seconds: float | None,
) -> tuple[str | None, str]:
    combined = f"{artist} {title}"
    if _has_cyrillic(combined):
        queries = [f"{artist} - {title} текст", f"{artist} {title} lyrics"]
    else:
        queries = [f"{artist} - {title} lyrics", f"{artist} {title} текст песни"]

    all_urls: list[str] = []
    seen: set[str] = set()
    for query in queries:
        for url in _ddg_search(query):
            if url not in seen:
                seen.add(url)
                all_urls.append(url)

    trusted = [u for u in all_urls if any(_get_domain(u) == d or _get_domain(u).endswith("." + d) for d in _TRUSTED_LYRICS_DOMAINS)]
    untrusted = [u for u in all_urls if u not in trusted]
    ordered = trusted + untrusted[:3]

    for url in ordered[:WEB_SEARCH_MAX_PAGES]:
        domain = _get_domain(url)
        try:
            resp = _http_session.get(
                url,
                headers={"User-Agent": WEB_SEARCH_USER_AGENT},
                timeout=WEB_FETCH_TIMEOUT_SECONDS,
            )
            if resp.status_code != 200:
                continue
            if len(resp.content) > WEB_MAX_RESPONSE_BYTES:
                continue
            if "charset" not in resp.headers.get("Content-Type", "").lower():
                resp.encoding = resp.apparent_encoding
            lyrics = _extract_lyrics_from_html(resp.text)
            if lyrics and _validate_lyrics(lyrics, duration_seconds, artist, title):
                log.info("Web search: found lyrics on %s (%d chars)", domain, len(lyrics))
                return lyrics, f"web-{domain}"
        except Exception as e:
            log.debug("Web search: failed to fetch %s: %s", domain, e)

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
            "Candidate [%s] query='%s - %s': %s, %d lines",
            source, src_artist, src_title,
            "synced" if is_synced else "plain",
            _count_lyric_lines(lyrics),
        )
        return False

    def _have_enough() -> bool:
        return len(candidates) >= 2

    for var_artist, var_title in variations:
        lyrics, source = _fetch_from_lrclib(var_artist, var_title, album, duration_seconds)
        if _try_add(lyrics, source, var_artist, var_title):
            return [], True
        album = None
        if _have_enough():
            break

    if not _have_enough():
        for var_artist, var_title in variations[:3]:
            lyrics, source = _fetch_from_syncedlyrics(var_artist, var_title)
            _try_add(lyrics, source, var_artist, var_title)
            if _have_enough():
                break

    if not _have_enough():
        for var_artist, var_title in variations[:2]:
            lyrics, source = _fetch_from_qqmusic(var_artist, var_title, duration_seconds)
            _try_add(lyrics, source, var_artist, var_title)
            if _have_enough():
                break

    if not candidates:
        for var_artist, var_title in variations[:2]:
            lyrics, source = _fetch_from_web_search(var_artist, var_title, duration_seconds)
            _try_add(lyrics, source, var_artist, var_title)
            if candidates:
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
                log.info("Cross-validation %s vs %s: similarity=%.2f", source_a, source_b, sim)
                if sim >= 0.35:
                    if not verified_synced:
                        if synced_a:
                            verified_synced = (lyrics_a, source_a)
                        elif synced_b:
                            verified_synced = (lyrics_b, source_b)
                    if not verified_plain:
                        verified_plain = (lyrics_a, source_a)

        if verified_synced:
            log.info("Cross-validated synced lyrics from %s", verified_synced[1])
            return verified_synced
        if verified_plain:
            log.info("Cross-validated plain lyrics from %s", verified_plain[1])
            return verified_plain

    synced = [(lyr, src) for lyr, src, sync in candidates if sync]
    if synced:
        log.info("Using best single-source synced lyrics from %s", synced[0][1])
        return synced[0]

    log.info("Using best single-source plain lyrics from %s", candidates[0][1])
    return candidates[0][0], candidates[0][1]


# ===========================================================================
# Lyrics: API endpoint
# ===========================================================================

@app.post("/api/fetch-lyrics", response_model=LyricsResponse)
def fetch_lyrics(request: LyricsRequest):
    output_path = Path(request.outputPath).resolve()

    if not _is_path_allowed(output_path):
        log.error("Path not in allowed roots: %s", output_path)
        raise HTTPException(status_code=403, detail="Invalid output path")

    if request.force and output_path.exists():
        log.info("Force mode: deleting existing lyrics file %s", output_path)
        output_path.unlink(missing_ok=True)

    if not request.force and output_path.exists() and output_path.stat().st_size > 0:
        content = output_path.read_text(encoding="utf-8").strip()
        if content != NEGATIVE_CACHE_MARKER:
            log.info("Lyrics already exist: %s", output_path)
            return LyricsResponse(
                success=True,
                outputPath=str(output_path),
                source="cached",
                synced=_has_lrc_timestamps(content),
                lyrics=content,
            )

    if not request.force and _is_negative_cache_valid(output_path):
        log.info("Negative cache hit for: %s - %s", request.artist, request.title)
        return LyricsResponse(success=False, source="negative-cache")

    artist = request.artist
    title = request.title
    album = request.album
    duration_seconds = request.durationMs / 1000.0 if request.durationMs else None

    log.info(
        "Searching lyrics for: '%s - %s'%s%s",
        artist, title,
        f" album='{album}'" if album else "",
        f" duration={duration_seconds:.0f}s" if duration_seconds else "",
    )

    variations = _generate_query_variations(artist, title)
    log.info("Generated %d query variations for '%s - %s'", len(variations), artist, title)

    candidates, is_instrumental = _search_all_sources(
        artist, title, album, duration_seconds, variations
    )

    if is_instrumental:
        log.info("Track '%s - %s' is instrumental, skipping lyrics", artist, title)
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="instrumental")

    if not candidates:
        log.info("No lyrics found for: '%s - %s' from any source", artist, title)
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="exhausted")

    result = _select_best_candidate(candidates)

    if not result:
        log.info("No valid candidate selected for: '%s - %s'", artist, title)
        _write_negative_cache(output_path)
        return LyricsResponse(success=False, source="exhausted")

    chosen_lyrics, chosen_source = result

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(chosen_lyrics, encoding="utf-8")
    is_synced = _has_lrc_timestamps(chosen_lyrics)
    log.info(
        "Wrote %s lyrics to %s (source: %s, candidates: %d)",
        "synced" if is_synced else "plain",
        output_path, chosen_source, len(candidates),
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
