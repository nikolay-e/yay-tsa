ALTER TABLE listening_session
    ADD COLUMN energy REAL,
    ADD COLUMN intensity REAL,
    ADD COLUMN mood_tags TEXT[],
    ADD COLUMN attention_mode VARCHAR(20) DEFAULT 'active';

UPDATE listening_session
SET
    energy = (state->>'energy')::real,
    intensity = (state->>'intensity')::real,
    mood_tags = (SELECT ARRAY(SELECT jsonb_array_elements_text(state->'moodTags'))
                 WHERE state->'moodTags' IS NOT NULL AND jsonb_typeof(state->'moodTags') = 'array'),
    attention_mode = COALESCE(state->>'attentionMode', 'active')
WHERE state IS NOT NULL AND state != '{}'::jsonb;
