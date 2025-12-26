import os
import time
import threading
import logging
import subprocess
import shutil
from pathlib import Path
from typing import Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

MODEL_NAME = os.getenv("MODEL_NAME", "htdemucs")
OUTPUT_FORMAT = os.getenv("OUTPUT_FORMAT", "wav")
USE_CUDA = os.getenv("DEVICE", "cuda") == "cuda"
ALLOWED_MEDIA_PREFIX = "/media"
JOB_TTL_SECONDS = 3600
FFMPEG_TIMEOUT_SECONDS = 300

job_progress: dict[str, dict] = {}
job_timestamps: dict[str, float] = {}
cleanup_lock = threading.Lock()


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


app = FastAPI(title="Audio Separator Service", version="2.0.0", lifespan=lifespan)

_separator_lock = threading.Lock()


def create_separator(output_dir: str):
    """Create a fresh Separator instance with the correct output_dir.

    The audio-separator library caches output_dir internally when model is loaded,
    so we must create a new instance for each output directory.
    Model weights are cached by the library itself (in ~/.cache/torch/hub/).
    """
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


def update_job_progress(track_id: str, data: dict):
    job_progress[track_id] = data
    job_timestamps[track_id] = time.time()


class SeparationRequest(BaseModel):
    inputPath: str
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


def run_separation(track_id: str, input_path: Path):
    start_time = time.time()
    song_name = input_path.stem
    karaoke_dir = input_path.parent / ".karaoke"

    try:
        update_job_progress(track_id, {
            "trackId": track_id,
            "state": "PROCESSING",
            "progress": 5,
            "message": "Preparing output directory",
        })

        karaoke_dir.mkdir(exist_ok=True)
        sep = create_separator(str(karaoke_dir))

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

        update_job_progress(track_id, {
            **job_progress.get(track_id, {}),
            "progress": 90,
            "message": "Creating final karaoke file",
        })

        instrumental_filename = f"{song_name}_instrumental.{OUTPUT_FORMAT}"
        karaoke_path = karaoke_dir / instrumental_filename

        if len(instrumental_stems) == 1:
            if instrumental_stems[0] != karaoke_path:
                shutil.move(str(instrumental_stems[0]), str(karaoke_path))
        else:
            try:
                cmd = ["ffmpeg", "-y"]
                for stem in instrumental_stems:
                    cmd.extend(["-i", str(stem)])
                filter_complex = f"amix=inputs={len(instrumental_stems)}:normalize=0"
                cmd.extend(["-filter_complex", filter_complex, str(karaoke_path)])
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
                    if stem != karaoke_path and stem.exists():
                        stem.unlink(missing_ok=True)

        vocal_path = ""
        if vocal_stem:
            vocal_filename = f"{song_name}_vocals.{OUTPUT_FORMAT}"
            vocal_dest = karaoke_dir / vocal_filename
            if vocal_stem != vocal_dest:
                shutil.move(str(vocal_stem), str(vocal_dest))
            vocal_path = str(vocal_dest)

        for leftover in karaoke_dir.glob(f"*_{MODEL_NAME}.{OUTPUT_FORMAT}"):
            if leftover.name not in [instrumental_filename, f"{song_name}_vocals.{OUTPUT_FORMAT}"]:
                leftover.unlink(missing_ok=True)
                log.info(f"Cleaned up leftover stem: {leftover}")

        elapsed_ms = int((time.time() - start_time) * 1000)

        update_job_progress(track_id, {
            "trackId": track_id,
            "state": "READY",
            "progress": 100,
            "message": "Complete",
            "result": {
                "instrumental_path": str(karaoke_path),
                "vocal_path": vocal_path,
                "processing_time_ms": elapsed_ms,
            }
        })

        log.info(f"Karaoke complete: instrumental={karaoke_path}, vocals={vocal_path}")

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
    track_id = request.trackId

    if not str(input_path).startswith(ALLOWED_MEDIA_PREFIX):
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

    update_job_progress(track_id, {
        "trackId": track_id,
        "state": "PROCESSING",
        "progress": 0,
        "message": "Starting separation",
    })

    run_separation(track_id, input_path)

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
