-- Restore admin test user that was removed by V2
-- Required for integration tests (YAYTSA_TEST_USERNAME=admin, YAYTSA_TEST_PASSWORD=admin123)

INSERT INTO users (username, password_hash, display_name, is_admin, is_active)
VALUES ('admin', '$2b$12$yifkwFq8B/pVoqRHnt391urAryBUo1BBPFL349AX/oL7YzfoHwI5O', 'Administrator', TRUE, TRUE)
ON CONFLICT (username) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    is_admin = EXCLUDED.is_admin,
    is_active = EXCLUDED.is_active;
