import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


@pytest.fixture
def seed_tracks_diverse():
    import numpy as np

    rng = np.random.default_rng(42)
    tracks = []
    for i in range(20):
        emb = rng.standard_normal(768).astype(np.float32)
        emb = emb / (np.linalg.norm(emb) + 1e-9)
        tracks.append(
            {
                "track_id": f"track-{i:03d}",
                "embedding_mert": emb.tolist(),
                "affinity_score": max(0.1, 1.0 - i * 0.05),
            }
        )
    return tracks


@pytest.fixture
def seed_tracks_identical():
    import numpy as np

    emb = np.ones(768, dtype=np.float32)
    emb = emb / np.linalg.norm(emb)
    return [
        {
            "track_id": f"identical-{i:03d}",
            "embedding_mert": emb.tolist(),
            "affinity_score": 1.0 - i * 0.1,
        }
        for i in range(10)
    ]
