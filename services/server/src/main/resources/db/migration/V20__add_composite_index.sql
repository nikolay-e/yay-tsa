-- Composite index to speed up the common /Items query pattern: filter by type, sort by sort_name
CREATE INDEX IF NOT EXISTS idx_items_type_sort_name ON items (type, sort_name);
