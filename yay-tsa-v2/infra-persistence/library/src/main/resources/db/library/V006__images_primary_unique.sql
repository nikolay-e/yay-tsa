DELETE FROM core_v2_library.images a
USING core_v2_library.images b
WHERE a.entity_id = b.entity_id
  AND a.is_primary
  AND b.is_primary
  AND a.id > b.id;

CREATE UNIQUE INDEX idx_images_one_primary
    ON core_v2_library.images (entity_id)
    WHERE is_primary;
