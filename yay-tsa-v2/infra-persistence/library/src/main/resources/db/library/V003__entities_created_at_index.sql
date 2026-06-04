-- "Recently added" browsing sorts tracks by (entity_type, created_at) via OffsetBasedPageRequest.
-- Without this composite index PostgreSQL scans by entity_type and then sorts by created_at on
-- every page request; the index makes pagination an index-ordered range scan.
CREATE INDEX idx_entities_type_created_at ON core_v2_library.entities (entity_type, created_at);
