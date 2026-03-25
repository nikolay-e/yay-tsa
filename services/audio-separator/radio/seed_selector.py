import numpy as np


class SeedSelector:
    def compute_seeds(self, tracks: list[dict], num_seeds: int = 10) -> dict:
        if len(tracks) < num_seeds:
            return {"seeds": [{"track_id": t["track_id"]} for t in tracks]}

        sorted_tracks = sorted(tracks, key=lambda t: t["affinity_score"], reverse=True)

        embeddings = np.array([t["embedding_mert"] for t in sorted_tracks], dtype=np.float32)
        norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
        norms = np.where(norms < 1e-9, 1.0, norms)
        embeddings = embeddings / norms

        seed_indices = [0]
        min_distances = 1.0 - embeddings @ embeddings[0]

        for _ in range(1, num_seeds):
            farthest = np.argmax(min_distances)
            seed_indices.append(int(farthest))

            new_distances = 1.0 - embeddings @ embeddings[farthest]
            min_distances = np.minimum(min_distances, new_distances)
            for idx in seed_indices:
                min_distances[idx] = -1.0

        seeds = []
        for idx in seed_indices:
            seeds.append({"track_id": sorted_tracks[idx]["track_id"]})

        return {"seeds": seeds}
