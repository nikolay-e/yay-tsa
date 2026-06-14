-- Diacritic-insensitive name search: folds "é"->"e" and Cyrillic "ё"->"е".
-- The bundled unaccent.rules (PostgreSQL 17) already maps both, so no custom rules are needed.
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

-- unaccent('unaccent', $1) is STABLE (its dictionary lives on the server), which bars the bare
-- function from expression indexes. The two-argument form with a hard-coded, schema-qualified
-- dictionary is deterministic, so wrapping it as IMMUTABLE is the canonical way to make it indexable.
CREATE OR REPLACE FUNCTION core_v2_library.f_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
AS $$
    SELECT public.unaccent('public.unaccent', $1)
$$;

-- Expression GIN trigram index whose expression matches the search predicate verbatim
-- (f_unaccent(lower(name))), so diacritic-folded ILIKE stays index-backed.
-- A plain (non-CONCURRENT) CREATE INDEX is used because Flyway runs the migration in a single
-- transaction and CONCURRENTLY cannot; the brief write lock is acceptable at personal-server scale.
CREATE INDEX idx_entities_name_unaccent_trgm
    ON core_v2_library.entities
    USING gin (core_v2_library.f_unaccent(lower(name)) gin_trgm_ops);
