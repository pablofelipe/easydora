-- The admin@easydora.com row seeded by V1/V3 has never had a real,
-- usable password: its password_hash
-- ('$2a$12$pQrStUvWxYzAbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdef') is a
-- structurally valid-looking but hand-typed placeholder, not the output
-- of actually hashing any password -- no credential logs in as this
-- account today. This went unnoticed because nothing in the project ever
-- checked the ADMIN role.
--
-- ADMIN is now a real, checked role (orders-service's fulfillment
-- actions), so a working operations account is needed. Rather than
-- committing any credential (hash or plaintext) to a migration, the real
-- account is bootstrapped at application startup from ADMIN_EMAIL /
-- ADMIN_PASSWORD environment variables (see AdminAccountInitializer) --
-- the same place any other real secret in this project's docker-compose
-- setup already lives. This migration only removes the dead, never-usable
-- placeholder row so the bootstrap starts from a clean slate.

DELETE FROM auth_schema.users
WHERE email = 'admin@easydora.com'
  AND password_hash = '$2a$12$pQrStUvWxYzAbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdef';
