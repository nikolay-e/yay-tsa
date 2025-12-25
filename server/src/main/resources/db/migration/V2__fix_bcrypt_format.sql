-- Update BCrypt hashes from $2b$ to $2a$ format for Spring Security compatibility
-- $2a$ and $2b$ are functionally equivalent, but Spring Security BCryptPasswordEncoder
-- generates $2a$ format hashes, so we update existing hashes for consistency

UPDATE users SET password_hash = '$2a$12$WxrvixD4QKSr3rddvF23CucfmCE7rEpccUy9KHxMssOOcdbwWfTp2' WHERE username = 'master';
UPDATE users SET password_hash = '$2a$12$kpScyXi4azVs/MWd72LN4euHSKrgHw9XAO0E5zYjBdUPUZW4VSaC2' WHERE username = 'teftel';
UPDATE users SET password_hash = '$2a$12$tMYi.E38g3uE2Z4AanDr7O.yAoU.iimRA8X6HkgMH4I1IhfSJg9T2' WHERE username = 'little';
UPDATE users SET password_hash = '$2a$12$K9hUrl1NX1sAWhztTQrsyeVl39BjXGFF0umP4sKATfbzm6NUC5VVm' WHERE username = 'test';
UPDATE users SET password_hash = '$2a$12$dw1SNhB9sUzFWw36cq85j.Kddhlb/GbxEdZO9xqYUkLqsoTjVktyW' WHERE username = 'middle';
