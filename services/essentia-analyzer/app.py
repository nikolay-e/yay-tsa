import os
import logging
from pathlib import Path
from typing import Optional

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

app = FastAPI(title="Essentia Audio Analyzer")

MODELS_DIR = Path(os.environ.get("MODELS_DIR", "/app/models"))
MODELS_DIR.mkdir(parents=True, exist_ok=True)

MODEL_URLS = {
    "msd-musicnn-1.pb": "https://essentia.upf.edu/models/feature-extractors/musicnn/msd-musicnn-1.pb",
    "mood_aggressive-musicnn-msd-2.pb": "https://essentia.upf.edu/models/classifiers/mood_aggressive/mood_aggressive-musicnn-msd-2.pb",
    "mood_happy-musicnn-msd-2.pb": "https://essentia.upf.edu/models/classifiers/mood_happy/mood_happy-musicnn-msd-2.pb",
    "mood_party-musicnn-msd-2.pb": "https://essentia.upf.edu/models/classifiers/mood_party/mood_party-musicnn-msd-2.pb",
    "mood_relaxed-musicnn-msd-2.pb": "https://essentia.upf.edu/models/classifiers/mood_relaxed/mood_relaxed-musicnn-msd-2.pb",
    "mood_sad-musicnn-msd-2.pb": "https://essentia.upf.edu/models/classifiers/mood_sad/mood_sad-musicnn-msd-2.pb",
    "deam-arousal-musicnn-msd-2.pb": "https://essentia.upf.edu/models/regressors/deam/deam-arousal-musicnn-msd-2.pb",
    "deam-valence-musicnn-msd-2.pb": "https://essentia.upf.edu/models/regressors/deam/deam-valence-musicnn-msd-2.pb",
    "danceability-musicnn-msd-2.pb": "https://essentia.upf.edu/models/classifiers/danceability/danceability-musicnn-msd-2.pb",
}

_models: dict = {}
_models_loaded = False


def _download_model(name: str, url: str) -> Path:
    import urllib.request
    path = MODELS_DIR / name
    if not path.exists():
        log.info("Downloading model %s …", name)
        urllib.request.urlretrieve(url, path)
        log.info("Downloaded %s (%.1f MB)", name, path.stat().st_size / 1_048_576)
    return path


def _load_models() -> None:
    global _models, _models_loaded
    if _models_loaded:
        return

    import essentia.standard as es  # type: ignore

    paths = {name: _download_model(name, url) for name, url in MODEL_URLS.items()}

    _models["embeddings"] = es.TensorflowPredictMusiCNN(
        graphFilename=str(paths["msd-musicnn-1.pb"]),
        output="model/dense/BiasAdd",
    )
    _models["aggressive"] = es.TensorflowPredict2D(
        graphFilename=str(paths["mood_aggressive-musicnn-msd-2.pb"]),
        output="model/Softmax",
    )
    _models["happy"] = es.TensorflowPredict2D(
        graphFilename=str(paths["mood_happy-musicnn-msd-2.pb"]),
        output="model/Softmax",
    )
    _models["party"] = es.TensorflowPredict2D(
        graphFilename=str(paths["mood_party-musicnn-msd-2.pb"]),
        output="model/Softmax",
    )
    _models["relaxed"] = es.TensorflowPredict2D(
        graphFilename=str(paths["mood_relaxed-musicnn-msd-2.pb"]),
        output="model/Softmax",
    )
    _models["sad"] = es.TensorflowPredict2D(
        graphFilename=str(paths["mood_sad-musicnn-msd-2.pb"]),
        output="model/Softmax",
    )
    _models["arousal"] = es.TensorflowPredict2D(
        graphFilename=str(paths["deam-arousal-musicnn-msd-2.pb"]),
        output="model/Identity",
    )
    _models["valence"] = es.TensorflowPredict2D(
        graphFilename=str(paths["deam-valence-musicnn-msd-2.pb"]),
        output="model/Identity",
    )
    _models["danceability"] = es.TensorflowPredict2D(
        graphFilename=str(paths["danceability-musicnn-msd-2.pb"]),
        output="model/Softmax",
    )
    _models_loaded = True
    log.info("All Essentia models loaded")


# Mood mapping: (binary classifier name, mood label, conditions)
# Maps Essentia's 5 binary classifiers + DEAM valence/arousal to our 8 categories.
def _map_mood(scores: dict, arousal: float, valence: float) -> str:
    # scores keys: aggressive, happy, party, relaxed, sad (probability of class=1)
    if scores["aggressive"] > 0.5:
        return "aggressive"
    if scores["sad"] > 0.5 and valence < 4.5:
        if arousal < 4.5:
            return "melancholic"
        return "sad"
    if scores["relaxed"] > 0.5:
        if valence > 5.5 and 3.5 < arousal < 6.0:
            return "nostalgic"
        return "calm"
    if scores["happy"] > 0.5:
        if arousal < 4.5 and valence > 6.0:
            return "romantic"
        return "happy"
    if scores["party"] > 0.5:
        return "energetic"
    # Fallback from highest score
    best = max(scores, key=lambda k: scores[k])
    fallback_map = {
        "happy": "happy",
        "sad": "sad",
        "relaxed": "calm",
        "party": "energetic",
        "aggressive": "aggressive",
    }
    return fallback_map.get(best, "calm")


class AnalyzeRequest(BaseModel):
    audioPath: str


class AnalyzeResponse(BaseModel):
    mood: str
    energy: int
    valence: int
    danceability: int
    arousal: int


def _scale_regression(value: float, src_min: float, src_max: float) -> int:
    """Scale a regression output [src_min, src_max] to [1, 10]."""
    clamped = max(src_min, min(src_max, float(value)))
    scaled = (clamped - src_min) / (src_max - src_min) * 9 + 1
    return max(1, min(10, round(scaled)))


@app.on_event("startup")
async def startup_event() -> None:
    try:
        _load_models()
    except Exception as e:
        log.error("Failed to load models on startup: %s", e)


@app.post("/api/analyze", response_model=AnalyzeResponse)
def analyze(request: AnalyzeRequest) -> AnalyzeResponse:
    audio_path = Path(request.audioPath)
    if not audio_path.exists():
        raise HTTPException(status_code=404, detail=f"Audio file not found: {audio_path}")

    try:
        _load_models()
    except Exception as e:
        log.error("Model loading failed: %s", e)
        raise HTTPException(status_code=503, detail="Models not ready")

    try:
        import essentia.standard as es  # type: ignore

        audio = es.MonoLoader(sampleRate=16000, filename=str(audio_path))()
        embeddings = _models["embeddings"](audio)

        def _binary_prob(model_key: str) -> float:
            preds = _models[model_key](embeddings)
            return float(np.mean(preds[:, 1]))

        scores = {
            "aggressive": _binary_prob("aggressive"),
            "happy": _binary_prob("happy"),
            "party": _binary_prob("party"),
            "relaxed": _binary_prob("relaxed"),
            "sad": _binary_prob("sad"),
        }

        arousal_raw = float(np.mean(_models["arousal"](embeddings)))
        valence_raw = float(np.mean(_models["valence"](embeddings)))

        # DEAM model outputs values roughly in [1, 9]
        arousal_scaled = _scale_regression(arousal_raw, 1.0, 9.0)
        valence_scaled = _scale_regression(valence_raw, 1.0, 9.0)

        dance_preds = _models["danceability"](embeddings)
        danceability_scaled = _scale_regression(float(np.mean(dance_preds[:, 1])), 0.0, 1.0)

        mood = _map_mood(scores, float(arousal_raw), float(valence_raw))

        return AnalyzeResponse(
            mood=mood,
            energy=arousal_scaled,
            valence=valence_scaled,
            danceability=danceability_scaled,
            arousal=arousal_scaled,
        )

    except HTTPException:
        raise
    except Exception as e:
        log.error("Analysis failed for %s: %s", audio_path, e)
        raise HTTPException(status_code=500, detail=f"Analysis failed: {e}")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "models_loaded": _models_loaded}
