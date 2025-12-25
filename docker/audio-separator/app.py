import os
import re
import shutil
import time
import threading
import logging
from pathlib import Path
from typing import Optional
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, BackgroundTasks
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "htdemucs")
OUTPUT_FORMAT = os.getenv("OUTPUT_FORMAT", "WAV")
USE_CUDA = os.getenv("DEVICE", "cuda") == "cuda"
ALLOWED_MEDIA_PREFIXES = ["/media", "/app/stems"]
JOB_TTL_SECONDS = 3600
FFMPEG_TIMEOUT_SECONDS = 300

job_progress: dict[str, dict] = {}
job_timestamps: dict[str, float] = {}
executor = ThreadPoolExecutor(max_workers=2)
cleanup_lock = threading.Lock()


def validate_path(path: Path, allowed_prefixes: list[str]) -> bool:
    resolved = path.resolve()
    return any(str(resolved).startswith(prefix) for prefix in allowed_prefixes)


def sanitize_filename(filename: str) -> str:
    return re.sub(r'[^\w\-_\.]', '_', filename)


def cleanup_expired_jobs():
    with cleanup_lock:
        now = time.time()
        expired = [
            track_id for track_id, ts in job_timestamps.items()
            if now - ts > JOB_TTL_SECONDS
        ]
        for track_id in expired:
            job_progress.pop(track_id, None)
            job_timestamps.pop(track_id, None)
            log.debug(f"Cleaned up expired job: {track_id}")


def periodic_cleanup():
    while True:
        time.sleep(300)
        cleanup_expired_jobs()


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info(f"Model {MODEL_NAME} will be loaded on first request")

    cleanup_thread = threading.Thread(target=periodic_cleanup, daemon=True)
    cleanup_thread.start()

    yield

    executor.shutdown(wait=False)


app = FastAPI(title="Audio Separator Service", version="1.0.0", lifespan=lifespan)


_separator_instance = None
_separator_lock = threading.Lock()


def get_separator(output_dir: str):
    global _separator_instance
    from audio_separator.separator import Separator

    with _separator_lock:
        if _separator_instance is None:
            log.info(f"Initializing Separator with model {MODEL_NAME}")
            _separator_instance = Separator(
                output_dir=output_dir,
                output_format=OUTPUT_FORMAT,
            )
            _separator_instance.load_model(model_filename=MODEL_NAME)
            log.info("Model loaded successfully")
        else:
            _separator_instance.output_dir = output_dir

    return _separator_instance


def update_job_progress(track_id: str, data: dict):
    job_progress[track_id] = data
    job_timestamps[track_id] = time.time()


class SeparationRequest(BaseModel):
    inputPath: str
    outputDir: str
    trackId: str


class SeparationResponse(BaseModel):
    instrumental_path: str
    vocal_path: str
    processing_time_ms: int


class ProgressResponse(BaseModel):
    trackId: str
    state: str
    progress: int
    message: Optional[str] = None
    result: Optional[SeparationResponse] = None


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


@app.get("/api/progress/{track_id}", response_model=ProgressResponse)
def get_progress(track_id: str):
    if track_id not in job_progress:
        return ProgressResponse(
            trackId=track_id,
            state="NOT_STARTED",
            progress=0,
        )
    return ProgressResponse(**job_progress[track_id])


def run_separation(track_id: str, input_path: Path, output_dir: Path):
    import subprocess

    start_time = time.time()

    try:
        update_job_progress(track_id, {
            "trackId": track_id,
            "state": "PROCESSING",
            "progress": 5,
            "message": "Loading model",
        })

        sep = get_separator(str(output_dir))

        update_job_progress(track_id, {
            **job_progress.get(track_id, {}),
            "progress": 10,
            "message": "Separating audio (AI processing)",
        })

        output_files = sep.separate(str(input_path))

        update_job_progress(track_id, {
            **job_progress.get(track_id, {}),
            "progress": 85,
            "message": "Processing output files",
        })

        log.info(f"Separation output files: {output_files}")

        abs_output_files = []
        for f in output_files:
            # audio-separator may return relative paths or paths in subdirectories
            file_path = Path(f)
            if not file_path.is_absolute():
                # Try output_dir first, then search subdirectories
                candidate = output_dir / f
                if not candidate.exists():
                    # Demucs creates subdirectories like htdemucs/track_name/stem.wav
                    # Search for the file in output_dir tree
                    filename = file_path.name
                    found = list(output_dir.rglob(filename))
                    if found:
                        candidate = found[0]
                        log.info(f"Found stem file at: {candidate}")
                    else:
                        log.error(f"Stem file not found: {f} (searched in {output_dir})")
                        raise Exception(f"Stem file not found: {f}")
                file_path = candidate

            resolved = file_path.resolve()
            if not validate_path(resolved, ALLOWED_MEDIA_PREFIXES):
                log.error(f"Path validation failed for output file: {resolved}")
                raise Exception(f"Invalid output path: {f}")
            abs_output_files.append(str(resolved))

        vocal_path = None
        instrumental_stems = []

        for output_file in abs_output_files:
            file_lower = output_file.lower()
            if "vocals" in file_lower:
                vocal_path = output_file
            elif "instrumental" in file_lower or "no_vocals" in file_lower:
                instrumental_stems = [output_file]
                break
            else:
                instrumental_stems.append(output_file)

        if not instrumental_stems:
            raise Exception(f"No instrumental stems in outputs: {output_files}")

        update_job_progress(track_id, {
            **job_progress.get(track_id, {}),
            "progress": 90,
            "message": "Mixing instrumental tracks",
        })

        if len(instrumental_stems) == 1:
            instrumental_path = instrumental_stems[0]
        else:
            instrumental_path = str(output_dir / f"instrumental.{OUTPUT_FORMAT}")
            cmd = ["ffmpeg", "-y"]
            for stem in instrumental_stems:
                cmd.extend(["-i", stem])
            filter_complex = f"amix=inputs={len(instrumental_stems)}:normalize=0"
            cmd.extend(["-filter_complex", filter_complex, instrumental_path])
            log.info(f"Mixing stems with timeout {FFMPEG_TIMEOUT_SECONDS}s")
            try:
                result = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=FFMPEG_TIMEOUT_SECONDS
                )
                if result.returncode != 0:
                    raise Exception(f"FFmpeg failed: {result.stderr}")
            except subprocess.TimeoutExpired:
                raise Exception(f"FFmpeg timed out after {FFMPEG_TIMEOUT_SECONDS}s")

            for stem in instrumental_stems:
                Path(stem).unlink(missing_ok=True)

        elapsed_ms = int((time.time() - start_time) * 1000)

        update_job_progress(track_id, {
            "trackId": track_id,
            "state": "READY",
            "progress": 100,
            "message": "Complete",
            "result": {
                "instrumental_path": instrumental_path,
                "vocal_path": vocal_path or "",
                "processing_time_ms": elapsed_ms,
            }
        })

    except Exception as e:
        log.exception(f"Separation failed for {track_id}: {e}")
        update_job_progress(track_id, {
            "trackId": track_id,
            "state": "FAILED",
            "progress": 0,
            "message": str(e),
        })


@app.post("/api/separate", response_model=SeparationResponse)
def separate_audio(request: SeparationRequest):
    input_path = Path(request.inputPath).resolve()
    output_dir = Path(request.outputDir).resolve()
    track_id = request.trackId

    if not validate_path(input_path, ALLOWED_MEDIA_PREFIXES):
        log.error(f"Path traversal attempt in input path: {input_path}")
        raise HTTPException(status_code=403, detail="Invalid input path")

    if not validate_path(output_dir, ALLOWED_MEDIA_PREFIXES):
        log.error(f"Path traversal attempt in output path: {output_dir}")
        raise HTTPException(status_code=403, detail="Invalid output path")

    if not input_path.exists():
        raise HTTPException(status_code=404, detail=f"Input file not found: {input_path}")

    output_dir.mkdir(parents=True, exist_ok=True)

    update_job_progress(track_id, {
        "trackId": track_id,
        "state": "PROCESSING",
        "progress": 0,
        "message": "Starting separation",
    })

    run_separation(track_id, input_path, output_dir)

    final_status = job_progress.get(track_id, {})
    if final_status.get("state") == "FAILED":
        raise HTTPException(status_code=500, detail=final_status.get("message", "Separation failed"))

    result = final_status.get("result", {})
    return SeparationResponse(
        instrumental_path=result.get("instrumental_path", ""),
        vocal_path=result.get("vocal_path", ""),
        processing_time_ms=result.get("processing_time_ms", 0),
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
