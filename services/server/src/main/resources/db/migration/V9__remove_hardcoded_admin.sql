-- Remove the hardcoded admin/admin123 user created by V7.
-- Admin user provisioning is now handled at application startup
-- via ADMIN_USERNAME / ADMIN_PASSWORD environment variables.

DELETE FROM api_tokens WHERE user_id = (SELECT id FROM users WHERE username = 'admin');
DELETE FROM users WHERE username = 'admin';
