-- V001 created these columns as JSONB, but LlmDecisionEntity / PlaybackSignalEntity map
-- them as String with columnDefinition = "TEXT" (opaque JSON strings, never queried with
-- jsonb operators). Hibernate ddl-auto=validate rejects the jsonb/text mismatch on a fresh
-- database. Production already runs them as TEXT (V001 drifted to JSONB after provisioning).
-- Convert jsonb -> text only where still jsonb, so this is a true no-op (no table rewrite,
-- no lock) on databases that are already text.
DO $$
BEGIN
    IF (SELECT data_type FROM information_schema.columns
        WHERE table_schema = 'core_v2_adaptive' AND table_name = 'llm_decisions'
          AND column_name = 'edits') = 'jsonb' THEN
        ALTER TABLE core_v2_adaptive.llm_decisions
            ALTER COLUMN intent TYPE text USING intent::text,
            ALTER COLUMN edits TYPE text USING edits::text,
            ALTER COLUMN validation_details TYPE text USING validation_details::text;
    END IF;

    IF (SELECT data_type FROM information_schema.columns
        WHERE table_schema = 'core_v2_adaptive' AND table_name = 'playback_signals'
          AND column_name = 'context') = 'jsonb' THEN
        ALTER TABLE core_v2_adaptive.playback_signals
            ALTER COLUMN context TYPE text USING context::text;
    END IF;
END $$;
