-- The outbox table was originally created by Hibernate ddl-auto from OutboxEntity (a bare String
-- maps to varchar(255)); Flyway later baselined over that live schema, so V001's TEXT declaration
-- never reached production. Device-command payloads carry a JSON list of track ids, so any
-- SET_QUEUE / ENQUEUE with more than ~2 tracks exceeded 255 chars and the insert failed with
-- SQLState 22001 (value too long) — silently breaking radio, batch enqueue, and cross-device
-- queue transfer. Force the column to TEXT so payloads of any size persist. Idempotent: a fresh
-- database created from V001 already has TEXT and this ALTER is a no-op.
ALTER TABLE core_v2_shared.outbox ALTER COLUMN payload TYPE TEXT;
