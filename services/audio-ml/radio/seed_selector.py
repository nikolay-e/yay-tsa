import numpy as np


class SeedSelector:
    def __init__(self, seed=None):
        self._rng = np.random.default_rng(seed)

    def compute_seeds(
        self, tracks: list[dict], num_seeds: int = 10, temperature: float = 0.7
    ) -> dict:
        if len(tracks) < num_seeds:
            return {"seeds": [{"track_id": t["track_id"]} for t in tracks]}

        sorted_tracks = sorted(tracks, key=lambda t: t["affinity_score"], reverse=True)

        embeddings = np.array([t["embedding_mert"] for t in sorted_tracks], dtype=np.float32)
        norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
        norms = np.where(norms < 1e-9, 1.0, norms)
        embeddings = embeddings / norms

        affinities = np.array([t["affinity_score"] for t in sorted_tracks], dtype=np.float64)
        affinities = np.maximum(affinities, 1e-9)

        top_k = min(max(5, num_seeds), len(sorted_tracks))
        top_weights = affinities[:top_k]
        top_probs = top_weights / top_weights.sum()
        first_idx = int(self._rng.choice(top_k, p=top_probs))

        seed_indices = [first_idx]
        min_distances = 1.0 - embeddings @ embeddings[first_idx]
        min_distances[first_idx] = -np.inf

        for _ in range(1, num_seeds):
            scores = min_distances.copy()
            scores[scores < 0] = -np.inf

            scaled = scores / max(temperature, 1e-6)
            scaled = scaled - np.max(scaled[scaled > -np.inf])
            exp_scores = np.exp(np.clip(scaled, -50, 50))
            exp_scores[min_distances < 0] = 0.0

            total = exp_scores.sum()
            if total < 1e-12:
                break
            probs = exp_scores / total

            chosen = int(self._rng.choice(len(probs), p=probs))
            seed_indices.append(chosen)

            new_distances = 1.0 - embeddings @ embeddings[chosen]
            min_distances = np.minimum(min_distances, new_distances)
            for idx in seed_indices:
                min_distances[idx] = -np.inf

        seeds = [{"track_id": sorted_tracks[idx]["track_id"]} for idx in seed_indices]
        return {"seeds": seeds}
