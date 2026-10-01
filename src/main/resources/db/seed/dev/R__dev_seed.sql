-- Development and test seed data (profiles dev and test only; never on the prod Flyway path).
-- All data is synthetic. Student numbers use the reserved fake range 9000001+.
-- The password columns hold BCrypt hashes (strength 10, the application's PasswordEncoder) of the
-- local demo password; no plaintext password is stored in this file.
-- Repeatable migration: Flyway runs it after all versioned migrations and again whenever this file
-- changes. ON CONFLICT DO NOTHING makes every run idempotent (also on databases baselined from an
-- earlier ddl-auto schema). Prod ignores it in history via ignore-migration-patterns=repeatable:missing.

INSERT INTO course (name, term, instructor) VALUES
    ('Advanced Web Development', '2026/1', 'Instructor Alpha'),
    ('Data Structures and Algorithms', '2026/1', 'Instructor Beta'),
    ('Systems Programming (Rust)', '2026/2', 'Instructor Gamma'),
    ('Fundamentals of AI', '2026/2', 'Instructor Delta')
ON CONFLICT DO NOTHING;

INSERT INTO account (username, password, first_name, last_name, student_number, role, deleted) VALUES
    ('admin', '$2a$10$tHaHKcM5Ya/ZlhnyaVimVOzROlbmEUb.IJa2GbitjbErBngyV5G.a', 'Admin', 'Bey', '9000001', 'ADMIN', 0),
    ('ayberk', '$2a$10$SYmfoxlKNNHzbN.EsZK/8OViTByf6F9m.kt4/S.PAl2VD31suwA1i', 'Demo', 'Student', '9000002', 'USER', 0),
    ('ali', '$2a$10$aGce0Re.8Ds8APZ84v9rP.nu9WF.wsXWq6RlbekyPoftj6f5cFNOi', 'Ali', 'Yilmaz', '9000003', 'USER', 0)
ON CONFLICT DO NOTHING;
