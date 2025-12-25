-- Remove default admin user and ensure configured users exist
-- Uses ON CONFLICT DO NOTHING to preserve existing user passwords

DELETE FROM users WHERE username = 'admin';

-- Only insert users that don't exist - preserves existing passwords
INSERT INTO users (username, password_hash, display_name, is_admin, is_active) VALUES
('master', '$2b$12$A6I/7yFvVal4bllP.LgBc./D6lrtGDm2Ju3XhKOpMuwumDU3MBsdq', 'master', TRUE, TRUE),
('teftel', '$2b$12$dyW51ktPKsW/jVYYr.tISO/GTe.r49nrmtu/cuCYrTLtjls2ntdfS', 'teftel', TRUE, TRUE),
('little', '$2b$12$AkrjoH30qqiCYduiYeuet.3CwOTklUZezgZS7gT1S1inyPEkYZ0na', 'little', TRUE, TRUE),
('test', '$2b$12$Zzs/EoH8RjSUBbSEOVXpc.6IZpZeTeLvgPLbgqh1TGkuyXuJLng5K', 'test', TRUE, TRUE),
('middle', '$2b$12$9t7.xdPyjiQq/nUz2HABI.bxm64geGX6MbeiVe/uHIUz1cx.PAfiS', 'middle', TRUE, TRUE)
ON CONFLICT (username) DO NOTHING;
