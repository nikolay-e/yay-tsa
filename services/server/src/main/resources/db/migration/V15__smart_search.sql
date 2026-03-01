-- Smart search: cross-field search with transliteration support
-- Replaces the V5 generated search_vector column with a denormalized search_text column
-- that includes data from related entities (artist, album, genres)

-- 1. Drop old tsvector-based search infrastructure from V5
DROP INDEX IF EXISTS items_search_vector_idx;
ALTER TABLE items DROP COLUMN IF EXISTS search_vector;

-- 2. Add search_text column (populated by application during scan)
ALTER TABLE items ADD COLUMN IF NOT EXISTS search_text TEXT;

-- 3. GIN trigram index for fast ILIKE queries
CREATE INDEX IF NOT EXISTS idx_items_search_text_trgm
    ON items USING gin (search_text gin_trgm_ops);

-- 4. Backfill search_text for existing items from related entities
-- Tracks: track name + artist name + album name + genre names
UPDATE items SET search_text = lower(
    coalesce(items.name, '') || ' ' ||
    coalesce(items.sort_name, '') || ' ' ||
    coalesce(album.name, '') || ' ' ||
    coalesce(artist.name, '') || ' ' ||
    coalesce(genre_agg.names, '')
)
FROM audio_tracks at
LEFT JOIN items album ON album.id = at.album_id
LEFT JOIN items artist ON artist.id = at.album_artist_id
LEFT JOIN LATERAL (
    SELECT string_agg(g.name, ' ') AS names
    FROM item_genres ig
    JOIN genres g ON g.id = ig.genre_id
    WHERE ig.item_id = at.item_id
) genre_agg ON true
WHERE at.item_id = items.id AND items.type = 'AudioTrack';

-- Albums: album name + artist name + genre names
UPDATE items SET search_text = lower(
    coalesce(items.name, '') || ' ' ||
    coalesce(items.sort_name, '') || ' ' ||
    coalesce(artist.name, '') || ' ' ||
    coalesce(genre_agg.names, '')
)
FROM albums alb
LEFT JOIN items artist ON artist.id = alb.artist_id
LEFT JOIN LATERAL (
    SELECT string_agg(g.name, ' ') AS names
    FROM item_genres ig
    JOIN genres g ON g.id = ig.genre_id
    WHERE ig.item_id = alb.item_id
) genre_agg ON true
WHERE alb.item_id = items.id AND items.type = 'MusicAlbum';

-- Artists: artist name + genre names (genres from their tracks)
UPDATE items SET search_text = lower(
    coalesce(items.name, '') || ' ' ||
    coalesce(items.sort_name, '') || ' ' ||
    coalesce(genre_agg.names, '')
)
FROM artists art
LEFT JOIN LATERAL (
    SELECT string_agg(DISTINCT g.name, ' ') AS names
    FROM audio_tracks at2
    JOIN item_genres ig ON ig.item_id = at2.item_id
    JOIN genres g ON g.id = ig.genre_id
    WHERE at2.album_artist_id = art.item_id
) genre_agg ON true
WHERE art.item_id = items.id AND items.type = 'MusicArtist';

-- Fallback: any remaining items without search_text
UPDATE items SET search_text = lower(coalesce(name, '') || ' ' || coalesce(sort_name, ''))
WHERE search_text IS NULL;
