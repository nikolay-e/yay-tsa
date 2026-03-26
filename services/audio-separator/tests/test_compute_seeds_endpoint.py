import numpy as np
import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def client():
    from app import app

    return TestClient(app)


def _to_payload(tracks, num_seeds=None):
    payload = {
        "tracks": [
            {
                "track_id": t["track_id"],
                "embedding_mert": t["embedding_mert"],
                "affinity_score": t["affinity_score"],
            }
            for t in tracks
        ],
    }
    if num_seeds is not None:
        payload["num_seeds"] = num_seeds
    return payload


class TestComputeSeedsEndpoint:
    def test_returns_unique_seed_subset(self, client, seed_tracks_diverse):
        response = client.post(
            "/api/v1/radio/compute-seeds",
            json=_to_payload(seed_tracks_diverse, num_seeds=7),
        )

        assert response.status_code == 200
        seeds = response.json()["seeds"]
        ids = [s["track_id"] for s in seeds]
        assert len(ids) == 7
        assert len(ids) == len(set(ids))
        assert set(ids).issubset({t["track_id"] for t in seed_tracks_diverse})

    def test_rejects_insufficient_input(self, client):
        empty = client.post("/api/v1/radio/compute-seeds", json=_to_payload([], num_seeds=1))
        assert empty.status_code == 400

        single = client.post(
            "/api/v1/radio/compute-seeds",
            json=_to_payload(
                [{"track_id": "x", "embedding_mert": [0.1] * 768, "affinity_score": 1.0}],
                num_seeds=1,
            ),
        )
        assert single.status_code == 400

    def test_handles_large_payload(self, client):
        rng = np.random.default_rng(123)
        tracks = []
        for i in range(100):
            emb = rng.standard_normal(768).astype(np.float32)
            emb = (emb / (np.linalg.norm(emb) + 1e-9)).tolist()
            tracks.append(
                {"track_id": f"big-{i}", "embedding_mert": emb, "affinity_score": 1.0 - i * 0.005}
            )

        response = client.post(
            "/api/v1/radio/compute-seeds", json=_to_payload(tracks, num_seeds=20)
        )

        assert response.status_code == 200
        assert len(response.json()["seeds"]) == 20
