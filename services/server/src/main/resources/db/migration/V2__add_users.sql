-- Remove default admin user and add/update configured users
-- BCrypt hashes are in $2a$ format for Spring Security compatibility
-- Uses ON CONFLICT to handle existing users (update password hash to new format)

DELETE FROM users WHERE username = 'admin';

INSERT INTO users (username, password_hash, display_name, is_admin, is_active) VALUES
('master', '$2a$12$WxrvixD4QKSr3rddvF23CucfmCE7rEpccUy9KHxMssOOcdbwWfTp2', 'master', TRUE, TRUE),
('teftel', '$2a$12$kpScyXi4azVs/MWd72LN4euHSKrgHw9XAO0E5zYjBdUPUZW4VSaC2', 'teftel', TRUE, TRUE),
('little', '$2a$12$tMYi.E38g3uE2Z4AanDr7O.yAoU.iimRA8X6HkgMH4I1IhfSJg9T2', 'little', TRUE, TRUE),
('test', '$2a$12$K9hUrl1NX1sAWhztTQrsyeVl39BjXGFF0umP4sKATfbzm6NUC5VVm', 'test', TRUE, TRUE),
('middle', '$2a$12$dw1SNhB9sUzFWw36cq85j.Kddhlb/GbxEdZO9xqYUkLqsoTjVktyW', 'middle', TRUE, TRUE)
ON CONFLICT (username) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    is_admin = EXCLUDED.is_admin,
    is_active = EXCLUDED.is_active;
