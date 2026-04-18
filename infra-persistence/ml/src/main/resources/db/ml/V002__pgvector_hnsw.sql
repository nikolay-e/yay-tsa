-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Convert FLOAT4[] → vector(N) for HNSW indexing
ALTER TABLE core_v2_ml.track_features
    ALTER COLUMN embedding_discogs TYPE vector(1280) USING embedding_discogs::vector(1280),
    ALTER COLUMN embedding_musicnn TYPE vector(200) USING embedding_musicnn::vector(200),
    ALTER COLUMN embedding_clap TYPE vector(512) USING embedding_clap::vector(512),
    ALTER COLUMN embedding_mert TYPE vector(768) USING embedding_mert::vector(768);

ALTER TABLE core_v2_ml.taste_profiles
    ALTER COLUMN embedding_mert TYPE vector(768) USING embedding_mert::vector(768),
    ALTER COLUMN embedding_clap TYPE vector(512) USING embedding_clap::vector(512);

-- HNSW indexes for cosine similarity search
CREATE INDEX IF NOT EXISTS idx_track_features_discogs_hnsw
    ON core_v2_ml.track_features
    USING hnsw (embedding_discogs vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

CREATE INDEX IF NOT EXISTS idx_track_features_musicnn_hnsw
    ON core_v2_ml.track_features
    USING hnsw (embedding_musicnn vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

CREATE INDEX IF NOT EXISTS idx_track_features_clap_hnsw
    ON core_v2_ml.track_features
    USING hnsw (embedding_clap vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

CREATE INDEX IF NOT EXISTS idx_track_features_mert_hnsw
    ON core_v2_ml.track_features
    USING hnsw (embedding_mert vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

-- Additional performance indexes
CREATE INDEX IF NOT EXISTS idx_user_track_affinity_score
    ON core_v2_ml.user_track_affinity (user_id, affinity_score DESC);
