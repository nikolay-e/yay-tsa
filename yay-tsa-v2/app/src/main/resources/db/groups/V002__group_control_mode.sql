ALTER TABLE core_v2_groups.playback_group
    ADD COLUMN control_mode VARCHAR(16) NOT NULL DEFAULT 'host';
