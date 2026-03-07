ALTER TABLE play_state ADD COLUMN favorited_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE play_state ADD COLUMN favorite_position INTEGER;

-- Backfill existing favorites with approximate timestamps and positions
UPDATE play_state
SET favorited_at = COALESCE(updated_at, created_at),
    favorite_position = sub.rn
FROM (
  SELECT id,
         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY COALESCE(updated_at, created_at)) AS rn
  FROM play_state
  WHERE is_favorite = true
) sub
WHERE play_state.id = sub.id;

-- Partial indexes for favorite ordering queries
CREATE INDEX idx_play_state_favorite_position ON play_state (user_id, favorite_position)
  WHERE is_favorite = true;
CREATE INDEX idx_play_state_favorited_at ON play_state (user_id, favorited_at DESC)
  WHERE is_favorite = true;
