import type { AudioItem } from '../internal/models/types.js';

const NORMALIZATION_MIN_DB = -18;
const NORMALIZATION_MAX_DB = 12;

// ReplayGain rules: album gain when continuing an unshuffled same-album run (preserves
// intentional loudness differences between an album's tracks), track gain otherwise;
// whichever is missing falls back to the other. Values clamp to a safe window and
// convert dB → linear inside the engine (10^(dB/20) on the shared input gain bus).
export function resolveNormalizationGainDb(
  track: Pick<AudioItem, 'NormalizationGain' | 'AlbumNormalizationGain'>,
  isSameAlbumSequence: boolean
): number | null {
  const preferredGainDb = isSameAlbumSequence
    ? (track.AlbumNormalizationGain ?? track.NormalizationGain)
    : (track.NormalizationGain ?? track.AlbumNormalizationGain);
  if (preferredGainDb == null || !Number.isFinite(preferredGainDb)) return null;
  return Math.max(NORMALIZATION_MIN_DB, Math.min(NORMALIZATION_MAX_DB, preferredGainDb));
}
