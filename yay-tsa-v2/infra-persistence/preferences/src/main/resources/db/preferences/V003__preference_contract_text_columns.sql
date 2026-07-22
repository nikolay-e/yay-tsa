-- The preference-contract fields are meant to be free-form instruction text (a long dj_style,
-- multi-line hard_rules, etc.). V001 declared them TEXT, but drift between the migration baseline
-- and the live schema left the columns narrower in some environments, so a long dj_style tripped an
-- opaque DataIntegrityViolation surfaced as InvariantViolation("The request violates a data
-- constraint") — no field, no bound. The durable fix pairs a named domain-level bound
-- (PreferencesHandler.MAX_CONTRACT_FIELD_LENGTH) with a guaranteed-wider DB column: force all four
-- fields back to TEXT so the domain bound is always hit first with an actionable message.
ALTER TABLE core_v2_preferences.preference_contracts
    ALTER COLUMN hard_rules TYPE TEXT,
    ALTER COLUMN soft_prefs TYPE TEXT,
    ALTER COLUMN dj_style TYPE TEXT,
    ALTER COLUMN red_lines TYPE TEXT;
