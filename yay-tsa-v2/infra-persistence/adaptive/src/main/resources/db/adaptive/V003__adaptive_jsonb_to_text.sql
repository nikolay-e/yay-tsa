-- V001 created these as JSONB, but LlmDecisionEntity maps them as String with
-- columnDefinition = "TEXT" (they hold opaque JSON strings, never queried with
-- jsonb operators). Hibernate ddl-auto=validate rejects the jsonb/text mismatch on
-- a fresh database. Converting to text is lossless (jsonb::text preserves content)
-- and aligns the schema with the entity across all environments.
ALTER TABLE core_v2_adaptive.llm_decisions
    ALTER COLUMN intent TYPE text USING intent::text,
    ALTER COLUMN edits TYPE text USING edits::text,
    ALTER COLUMN validation_details TYPE text USING validation_details::text;

-- Same mismatch: playback_signals.context is JSONB in V001 but PlaybackSignalEntity
-- maps it as String with columnDefinition = "TEXT".
ALTER TABLE core_v2_adaptive.playback_signals
    ALTER COLUMN context TYPE text USING context::text;
