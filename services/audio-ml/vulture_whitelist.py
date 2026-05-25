"""Vulture whitelist — false positives for framework-managed code."""

# Pydantic BaseModel fields (serialized by FastAPI, not accessed directly)
inputPath  # noqa
trackId  # noqa
instrumental_path  # noqa
vocal_path  # noqa
processing_time_ms  # noqa
durationMs  # noqa
outputPath  # noqa
synced  # noqa
feature_extraction  # noqa
embedding_extraction  # noqa
file_path  # noqa
embedding_discogs  # noqa
embedding_musicnn  # noqa
embedding_clap  # noqa
embedding_mert  # noqa
affinity_score  # noqa
num_seeds  # noqa
temperature  # noqa
dimensions  # noqa

# FastAPI endpoint handlers (called by framework via decorators)
health_check  # noqa
separate_audio  # noqa
fetch_lyrics  # noqa
extract_features  # noqa
extract_features_batch  # noqa
extraction_status  # noqa
extract_embeddings  # noqa
extract_embeddings_batch  # noqa
encode_text_embedding  # noqa
embedding_status  # noqa
compute_seeds  # noqa

# FastAPI lifespan context manager
lifespan  # noqa

# Module-level feature flags (set conditionally, read by endpoints)
ESSENTIA_AVAILABLE  # noqa
EMBEDDING_AVAILABLE  # noqa
_analyzer  # noqa
_embedding_extractor  # noqa
