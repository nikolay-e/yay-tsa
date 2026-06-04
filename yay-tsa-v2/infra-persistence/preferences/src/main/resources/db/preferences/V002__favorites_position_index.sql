-- Favorites are loaded per user ordered by position (findByUserIdOrderByPosition). The primary
-- key (user_id, track_id) forces a sort step on every load; a (user_id, position) index lets the
-- read be a pure index-ordered scan, which matters on the hot /Items?IsFavorite=true path.
CREATE INDEX idx_favorites_user_position ON core_v2_preferences.favorites (user_id, position);
