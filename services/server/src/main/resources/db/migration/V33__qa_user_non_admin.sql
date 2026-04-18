-- Ensure the QA/test user is never an admin.
-- V2 mistakenly created it with is_admin=TRUE.
UPDATE users SET is_admin = FALSE WHERE username = 'test';
