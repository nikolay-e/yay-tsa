from radio.seed_selector import SeedSelector


class TestSeedSelector:
    def test_selects_diverse_seeds_from_input(self, seed_tracks_diverse):
        result = SeedSelector().compute_seeds(seed_tracks_diverse, num_seeds=8)

        ids = [s["track_id"] for s in result["seeds"]]
        assert len(ids) == 8
        assert len(ids) == len(set(ids))
        assert set(ids).issubset({t["track_id"] for t in seed_tracks_diverse})

    def test_highest_affinity_is_first_seed(self, seed_tracks_diverse):
        result = SeedSelector().compute_seeds(seed_tracks_diverse, num_seeds=5)

        assert result["seeds"][0]["track_id"] == "track-000"

    def test_no_duplicates_with_identical_embeddings(self, seed_tracks_identical):
        result = SeedSelector().compute_seeds(seed_tracks_identical, num_seeds=5)

        ids = [s["track_id"] for s in result["seeds"]]
        assert len(ids) == 5
        assert len(ids) == len(set(ids))

    def test_returns_all_when_fewer_than_requested(self):
        import numpy as np

        rng = np.random.default_rng(99)
        tracks = []
        for i in range(3):
            emb = rng.standard_normal(768).astype(np.float32)
            emb = emb / (np.linalg.norm(emb) + 1e-9)
            tracks.append(
                {
                    "track_id": f"few-{i}",
                    "embedding_mert": emb.tolist(),
                    "affinity_score": 0.5,
                }
            )

        result = SeedSelector().compute_seeds(tracks, num_seeds=10)

        assert len(result["seeds"]) == 3
        assert {s["track_id"] for s in result["seeds"]} == {t["track_id"] for t in tracks}
