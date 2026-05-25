import gc
import logging
import os
import threading
import time
from pathlib import Path

import numpy as np
from essentia.standard import (
    Danceability,
    Dissonance,
    DynamicComplexity,
    FrameGenerator,
    KeyExtractor,
    LoudnessEBUR128,
    MonoLoader,
    OnsetRate,
    RhythmExtractor2013,
    SpectralComplexity,
    SpectralPeaks,
    Spectrum,
    TensorflowPredict2D,
    TensorflowPredictEffnetDiscogs,
    TensorflowPredictMusiCNN,
    Windowing,
)

log = logging.getLogger(__name__)

AUDIO_SAMPLE_RATE = 44100
TF_SAMPLE_RATE = 16000
MIN_AUDIO_DURATION_SEC = 5
MAX_AUDIO_DURATION_SEC = 1800

TF_INPUT_PLACEHOLDER = "model/Placeholder"
TF_OUTPUT_SOFTMAX = "model/Softmax"


class EssentiaAnalyzer:
    def __init__(self, models_dir: str):
        self._models_dir = models_dir
        self._lock = threading.Lock()
        self._effnet = None
        self._musicnn = None
        self._mood_happy_clf = None
        self._mood_aggressive_clf = None
        self._voice_instrumental_clf = None
        self._danceability_clf = None
        self._initialized = False

    def _ensure_models(self):
        if self._initialized:
            return
        with self._lock:
            if self._initialized:
                return
            log.info("Loading Essentia TF models from %s", self._models_dir)
            start = time.time()

            self._effnet = TensorflowPredictEffnetDiscogs(
                graphFilename=os.path.join(self._models_dir, "discogs-effnet-bs64-1.pb"),
                output="PartitionedCall:1",
            )
            self._musicnn = TensorflowPredictMusiCNN(
                graphFilename=os.path.join(self._models_dir, "msd-musicnn-1.pb"),
                output="model/dense/BiasAdd",
            )
            self._mood_happy_clf = TensorflowPredict2D(
                graphFilename=os.path.join(self._models_dir, "mood_happy-discogs-effnet-1.pb"),
                input=TF_INPUT_PLACEHOLDER,
                output=TF_OUTPUT_SOFTMAX,
            )
            self._mood_aggressive_clf = TensorflowPredict2D(
                graphFilename=os.path.join(self._models_dir, "mood_aggressive-discogs-effnet-1.pb"),
                input=TF_INPUT_PLACEHOLDER,
                output=TF_OUTPUT_SOFTMAX,
            )
            self._voice_instrumental_clf = TensorflowPredict2D(
                graphFilename=os.path.join(self._models_dir, "voice_instrumental-msd-musicnn-1.pb"),
                input=TF_INPUT_PLACEHOLDER,
                output=TF_OUTPUT_SOFTMAX,
            )
            self._danceability_clf = TensorflowPredict2D(
                graphFilename=os.path.join(self._models_dir, "danceability-discogs-effnet-1.pb"),
                input=TF_INPUT_PLACEHOLDER,
                output=TF_OUTPUT_SOFTMAX,
            )

            self._initialized = True
            log.info("All Essentia TF models loaded in %.1fs", time.time() - start)

    def extract(self, file_path: str) -> dict:
        start = time.time()

        audio_44k = MonoLoader(filename=file_path, sampleRate=AUDIO_SAMPLE_RATE)()
        duration_sec = len(audio_44k) / AUDIO_SAMPLE_RATE

        if duration_sec < MIN_AUDIO_DURATION_SEC:
            log.warning(
                "Track too short (%.1fs) for TF models: %s", duration_sec, Path(file_path).name
            )
            elapsed_ms = int((time.time() - start) * 1000)
            return {
                "features": None,
                "embedding_discogs": None,
                "embedding_musicnn": None,
                "processing_time_ms": elapsed_ms,
            }

        if duration_sec > MAX_AUDIO_DURATION_SEC:
            log.warning(
                "Track too long (%.0fs > %ds limit), skipping: %s",
                duration_sec,
                MAX_AUDIO_DURATION_SEC,
                Path(file_path).name,
            )
            elapsed_ms = int((time.time() - start) * 1000)
            return {
                "features": None,
                "embedding_discogs": None,
                "embedding_musicnn": None,
                "processing_time_ms": elapsed_ms,
            }

        features = self._extract_scalar_features(audio_44k)

        del audio_44k
        gc.collect()

        self._ensure_models()

        audio_16k = MonoLoader(filename=file_path, sampleRate=TF_SAMPLE_RATE, resampleQuality=4)()

        effnet_embeddings = self._effnet(audio_16k)
        musicnn_embeddings = self._musicnn(audio_16k)

        del audio_16k
        gc.collect()

        tf_features = self._classify_with_embeddings(effnet_embeddings, musicnn_embeddings)
        features.update(tf_features)

        discogs_mean = effnet_embeddings.mean(axis=0).tolist()
        musicnn_mean = musicnn_embeddings.mean(axis=0).tolist()

        elapsed_ms = int((time.time() - start) * 1000)
        log.info("Extracted features for %s in %dms", Path(file_path).name, elapsed_ms)

        return {
            "features": features,
            "embedding_discogs": discogs_mean,
            "embedding_musicnn": musicnn_mean,
            "processing_time_ms": elapsed_ms,
        }

    @staticmethod
    def _to_scalar(val) -> float:
        if isinstance(val, np.ndarray):
            return float(val.mean()) if val.size > 0 else 0.0
        return float(val)

    def _extract_scalar_features(self, audio: np.ndarray) -> dict:
        s = self._to_scalar

        bpm, _, beats_conf, _, _ = RhythmExtractor2013()(audio)

        key, scale, key_conf = KeyExtractor()(audio)

        stereo = np.column_stack([audio, audio]).astype(np.float32)
        _, _, integrated, range_lu = LoudnessEBUR128()(stereo)

        dance_val, _ = Danceability()(audio)

        onset_rate_val, _ = OnsetRate()(audio)

        _, avg_loudness = DynamicComplexity()(audio)

        spec_complexity = self._compute_spectral_complexity(audio)
        dissonance_val = self._compute_dissonance(audio)

        return {
            "bpm": round(s(bpm), 1),
            "bpm_confidence": round(s(beats_conf), 3),
            "key": f"{key} {scale}",
            "key_confidence": round(s(key_conf), 3),
            "energy": round(max(0.0, min(1.0, (s(avg_loudness) + 25.0) / 15.0)), 3),
            "loudness_integrated": round(s(integrated), 1),
            "loudness_range": round(s(range_lu), 1),
            "danceability": round(s(dance_val), 3),
            "onset_rate": round(s(onset_rate_val), 2),
            "average_loudness": round(s(avg_loudness), 3),
            "spectral_complexity": round(s(spec_complexity), 2),
            "dissonance": round(s(dissonance_val), 3),
        }

    def _classify_with_embeddings(self, effnet_emb: np.ndarray, musicnn_emb: np.ndarray) -> dict:
        happy_preds = self._mood_happy_clf(effnet_emb)
        valence = float(happy_preds.mean(axis=0)[0])

        aggressive_preds = self._mood_aggressive_clf(effnet_emb)
        arousal = float(aggressive_preds.mean(axis=0)[0])

        vi_preds = self._voice_instrumental_clf(musicnn_emb)
        vocal_score = float(vi_preds.mean(axis=0)[1])

        dance_preds = self._danceability_clf(effnet_emb)
        danceability_tf = float(dance_preds.mean(axis=0)[0])

        return {
            "valence": round(valence, 3),
            "arousal": round(arousal, 3),
            "vocal_instrumental": round(vocal_score, 3),
            "danceability": round(danceability_tf, 3),
        }

    def _compute_spectral_complexity(self, audio: np.ndarray) -> float:
        frame_size = 2048
        hop_size = 1024
        windowing = Windowing(type="hann", size=frame_size)
        spectrum_algo = Spectrum(size=frame_size)
        complexity_algo = SpectralComplexity(sampleRate=AUDIO_SAMPLE_RATE)

        values = []
        for frame in FrameGenerator(audio, frameSize=frame_size, hopSize=hop_size):
            spec = spectrum_algo(windowing(frame))
            values.append(complexity_algo(spec))

        return float(np.mean(values)) if values else 0.0

    def _compute_dissonance(self, audio: np.ndarray) -> float:
        frame_size = 2048
        hop_size = 1024
        windowing = Windowing(type="hann", size=frame_size)
        spectrum_algo = Spectrum(size=frame_size)
        peaks_algo = SpectralPeaks(
            sampleRate=AUDIO_SAMPLE_RATE,
            maxPeaks=50,
            magnitudeThreshold=0.00001,
            orderBy="frequency",
        )
        dissonance_algo = Dissonance()

        values = []
        for frame in FrameGenerator(audio, frameSize=frame_size, hopSize=hop_size):
            spec = spectrum_algo(windowing(frame))
            freqs, mags = peaks_algo(spec)
            if len(freqs) > 1:
                values.append(dissonance_algo(freqs, mags))

        return float(np.mean(values)) if values else 0.0
