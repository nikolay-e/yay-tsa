import logging
import os
import threading
import time

import numpy as np

log = logging.getLogger(__name__)

CLAP_MODEL_ID = "laion/clap-htsat-fused"
MERT_MODEL_ID = "m-a-p/MERT-v1-95M"
MERT_REVISION = "12af15fef9d0ac838c3f475bfbbf26d2060dd4f5"

CLAP_SAMPLE_RATE = 48000
MERT_SAMPLE_RATE = 24000
CLAP_CHUNK_SEC = 10
MERT_CHUNK_SEC = 30
CLAP_DIM = 512
MERT_DIM = 768
TEXT_MAX_CHARS = 300


class DualEmbeddingExtractor:
    def __init__(self, cache_dir: str | None = None):
        self._cache_dir = cache_dir or os.getenv("HF_HOME", "/app/hf_cache")
        self._clap_lock = threading.Lock()
        self._mert_lock = threading.Lock()
        self._clap_model = None
        self._clap_processor = None
        self._mert_model = None
        self._mert_processor = None
        self._clap_initialized = False
        self._mert_initialized = False
        self._torch = None
        self._target_layer = int(os.getenv("MERT_TARGET_LAYER", "8"))

    def _ensure_torch(self):
        if self._torch is None:
            import torch

            self._torch = torch

    def _ensure_clap(self):
        if self._clap_initialized:
            return
        with self._clap_lock:
            if self._clap_initialized:
                return
            self._ensure_torch()
            log.info("Loading CLAP model (cache: %s)", self._cache_dir)
            start = time.time()

            from transformers import ClapModel, ClapProcessor

            self._clap_model = ClapModel.from_pretrained(CLAP_MODEL_ID, cache_dir=self._cache_dir)
            self._clap_model.eval()
            self._clap_processor = ClapProcessor.from_pretrained(
                CLAP_MODEL_ID, cache_dir=self._cache_dir
            )

            self._clap_initialized = True
            log.info("CLAP model loaded in %.1fs", time.time() - start)

    def _ensure_mert(self):
        if self._mert_initialized:
            return
        with self._mert_lock:
            if self._mert_initialized:
                return
            self._ensure_torch()
            log.info("Loading MERT model (cache: %s)", self._cache_dir)
            start = time.time()

            from transformers import AutoModel, Wav2Vec2FeatureExtractor

            self._mert_model = AutoModel.from_pretrained(
                MERT_MODEL_ID,
                cache_dir=self._cache_dir,
                trust_remote_code=True,
                revision=MERT_REVISION,
            )
            self._mert_model.eval()
            self._mert_processor = Wav2Vec2FeatureExtractor.from_pretrained(
                MERT_MODEL_ID,
                cache_dir=self._cache_dir,
                trust_remote_code=True,
                revision=MERT_REVISION,
            )

            self._mert_initialized = True
            log.info("MERT model loaded in %.1fs", time.time() - start)

    def extract(self, file_path: str) -> dict:
        import librosa

        start = time.time()

        clap_embedding = self._extract_clap(file_path, librosa)
        mert_embedding = self._extract_mert(file_path, librosa)

        elapsed_ms = int((time.time() - start) * 1000)
        log.info("Embedding extraction completed in %dms", elapsed_ms)

        return {
            "embedding_clap": clap_embedding,
            "embedding_mert": mert_embedding,
            "processing_time_ms": elapsed_ms,
        }

    def encode_text(self, text: str) -> list[float]:
        self._ensure_clap()

        if len(text) > TEXT_MAX_CHARS:
            log.warning(
                "Text input truncated from %d to %d chars (CLAP 77-token limit)",
                len(text),
                TEXT_MAX_CHARS,
            )
            text = text[:TEXT_MAX_CHARS]

        inputs = self._clap_processor(text=[text], return_tensors="pt", padding=True)
        with self._torch.no_grad():
            text_features = self._clap_model.get_text_features(**inputs)

        embedding = text_features[0].cpu().numpy()
        embedding = embedding / (np.linalg.norm(embedding) + 1e-9)
        return embedding.tolist()

    def _extract_clap(self, file_path: str, librosa) -> list[float] | None:
        try:
            self._ensure_clap()
            audio, _ = librosa.load(file_path, sr=CLAP_SAMPLE_RATE, mono=True)
            chunk_size = CLAP_CHUNK_SEC * CLAP_SAMPLE_RATE
            chunks = [audio[i : i + chunk_size] for i in range(0, len(audio), chunk_size)]
            chunks = [c for c in chunks if len(c) >= CLAP_SAMPLE_RATE]

            if not chunks:
                return None

            embeddings = []
            for chunk in chunks:
                inputs = self._clap_processor(
                    audio=[chunk], sampling_rate=CLAP_SAMPLE_RATE, return_tensors="pt"
                )
                with self._torch.no_grad():
                    audio_features = self._clap_model.get_audio_features(**inputs)
                emb = audio_features[0].cpu().numpy()
                if emb.ndim > 1:
                    emb = emb.mean(axis=0)
                embeddings.append(emb)

            mean_embedding = np.mean(embeddings, axis=0).flatten()
            mean_embedding = mean_embedding / (np.linalg.norm(mean_embedding) + 1e-9)
            return mean_embedding.tolist()
        except Exception:
            log.exception("CLAP extraction failed")
            return None

    def _extract_mert(self, file_path: str, librosa) -> list[float] | None:
        try:
            self._ensure_mert()
            audio, _ = librosa.load(file_path, sr=MERT_SAMPLE_RATE, mono=True)
            chunk_size = MERT_CHUNK_SEC * MERT_SAMPLE_RATE
            chunks = [audio[i : i + chunk_size] for i in range(0, len(audio), chunk_size)]
            chunks = [c for c in chunks if len(c) >= MERT_SAMPLE_RATE]

            if not chunks:
                return None

            embeddings = []
            for chunk in chunks:
                inputs = self._mert_processor(
                    chunk, sampling_rate=MERT_SAMPLE_RATE, return_tensors="pt"
                )
                with self._torch.no_grad():
                    outputs = self._mert_model(**inputs, output_hidden_states=True)

                layer_idx = min(self._target_layer, len(outputs.hidden_states) - 1)
                hidden = outputs.hidden_states[layer_idx]
                chunk_embedding = hidden.mean(dim=1)[0].cpu().numpy()
                embeddings.append(chunk_embedding)

            mean_embedding = np.mean(embeddings, axis=0)
            mean_embedding = mean_embedding / (np.linalg.norm(mean_embedding) + 1e-9)
            return mean_embedding.tolist()
        except Exception:
            log.exception("MERT extraction failed")
            return None
