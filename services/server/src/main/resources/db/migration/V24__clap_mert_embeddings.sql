ALTER TABLE track_features
    ADD COLUMN embedding_clap vector(512),
    ADD COLUMN embedding_mert vector(768);

CREATE INDEX idx_tf_clap_hnsw ON track_features
    USING hnsw (embedding_clap vector_cosine_ops) WITH (m = 16, ef_construction = 128);

CREATE INDEX idx_tf_mert_hnsw ON track_features
    USING hnsw (embedding_mert vector_cosine_ops) WITH (m = 16, ef_construction = 128);
