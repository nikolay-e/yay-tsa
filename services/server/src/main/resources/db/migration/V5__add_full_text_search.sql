-- Add PostgreSQL Full-Text Search support for items

-- Create generated tsvector column for search
ALTER TABLE items ADD COLUMN IF NOT EXISTS search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(name, '') || ' ' || coalesce(sort_name, ''))
    ) STORED;

-- Create GIN index for fast full-text search
CREATE INDEX IF NOT EXISTS items_search_vector_idx ON items USING GIN (search_vector);
