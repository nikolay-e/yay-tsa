-- Restore original password hashes that were accidentally replaced by V2
-- These are the original $2b$ BCrypt hashes that match user passwords

UPDATE users SET password_hash = '$2b$12$A6I/7yFvVal4bllP.LgBc./D6lrtGDm2Ju3XhKOpMuwumDU3MBsdq' WHERE username = 'master';
UPDATE users SET password_hash = '$2b$12$dyW51ktPKsW/jVYYr.tISO/GTe.r49nrmtu/cuCYrTLtjls2ntdfS' WHERE username = 'teftel';
UPDATE users SET password_hash = '$2b$12$AkrjoH30qqiCYduiYeuet.3CwOTklUZezgZS7gT1S1inyPEkYZ0na' WHERE username = 'little';
UPDATE users SET password_hash = '$2b$12$Zzs/EoH8RjSUBbSEOVXpc.6IZpZeTeLvgPLbgqh1TGkuyXuJLng5K' WHERE username = 'test';
UPDATE users SET password_hash = '$2b$12$9t7.xdPyjiQq/nUz2HABI.bxm64geGX6MbeiVe/uHIUz1cx.PAfiS' WHERE username = 'middle';
