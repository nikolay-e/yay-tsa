-- Per-user taste clusters: read-model written by the taste-clusters batch job
-- (PCA + dynamic-k k-means over the user's affinity-track MERT embeddings).
-- Each row is one taste facet; representative_track_id is the cluster medoid.
-- Radio seeds read these (ordered by size) so the queue spans real taste facets
-- instead of a single averaged centroid.
CREATE TABLE core_v2_ml.taste_clusters (
    user_id                 UUID        NOT NULL,
    cluster_id              INT         NOT NULL,
    size                    INT         NOT NULL,
    representative_track_id UUID        NOT NULL,
    centroid_mert           vector(768),
    updated_at              TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, cluster_id)
);

CREATE INDEX idx_taste_clusters_user_size
    ON core_v2_ml.taste_clusters (user_id, size DESC);
