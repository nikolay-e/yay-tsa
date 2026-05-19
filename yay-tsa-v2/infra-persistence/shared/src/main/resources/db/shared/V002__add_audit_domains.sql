-- P2.F: Centralize repeated column patterns into reusable domain types and helper functions.
--
-- Background:
--   * `version BIGINT NOT NULL DEFAULT 0` repeats across 7+ tables (OCC column).
--   * `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` appears 27+ times.
--   * `VARCHAR(36)` is used 22+ times for ID columns where native UUID (16 bytes) is preferable.
--
-- This migration is ADDITIVE only. Existing tables retain their original column types.
-- A future maintenance window may retrofit legacy columns; that is out of scope here.
--
-- Migration style guide (V002+):
--   * Use `core_v2_shared.aggregate_version` for OCC version columns.
--   * Use `core_v2_shared.aggregate_id` (UUID) for new ID columns; varchar(36) only for
--     legacy-compatible columns where existing data prevents conversion.
--   * For `updated_at` columns, attach the `core_v2_shared.set_updated_at` BEFORE UPDATE
--     trigger to centralize the "touch timestamp on mutation" behavior.

-- ---------------------------------------------------------------------------
-- Domain types
-- ---------------------------------------------------------------------------

CREATE DOMAIN core_v2_shared.aggregate_version AS BIGINT;
COMMENT ON DOMAIN core_v2_shared.aggregate_version IS
    'OCC version column. Apply NOT NULL DEFAULT 0 at column definition site.';

CREATE DOMAIN core_v2_shared.aggregate_id AS UUID;
COMMENT ON DOMAIN core_v2_shared.aggregate_id IS
    'Native UUID id column. Prefer over VARCHAR(36) for new tables (16 vs 37 bytes).';

CREATE DOMAIN core_v2_shared.audit_created_at AS TIMESTAMPTZ;
COMMENT ON DOMAIN core_v2_shared.audit_created_at IS
    'Row creation timestamp. Apply NOT NULL DEFAULT now() at column definition site.';

CREATE DOMAIN core_v2_shared.audit_updated_at AS TIMESTAMPTZ;
COMMENT ON DOMAIN core_v2_shared.audit_updated_at IS
    'Row mutation timestamp. Attach set_updated_at BEFORE UPDATE trigger.';

-- ---------------------------------------------------------------------------
-- Helper trigger function for `updated_at` columns
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION core_v2_shared.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION core_v2_shared.set_updated_at() IS
    'BEFORE UPDATE trigger function. Sets NEW.updated_at to now() on every row update.';
