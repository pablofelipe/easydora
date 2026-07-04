-- Fixes a schema-duplication bug found while building the outbox pattern
-- (ADR-0003): V1/V2 ran against Flyway's resolved default schema
-- ("public", since no spring.flyway.schemas was configured), while
-- Hibernate's ddl-auto=update independently auto-created a second, real
-- copy of both tables in auth_schema (per hibernate.default_schema), which
-- is the copy the application actually reads/writes through JPA.
--
-- This migration makes auth_schema the one real, Flyway-tracked copy of
-- both tables, matching exactly what Hibernate's ddl-auto had silently
-- created there (verified column-by-column against the live database
-- before writing this file), and drops every dead table left behind:
-- the Hibernate-created auth_schema copies (about to be replaced by
-- Flyway-managed ones) and the orphaned public copies V1/V2 created.
-- spring.jpa.hibernate.ddl-auto is switched to validate in the same
-- change, so this is the last time any table in this service is created
-- by anything other than a tracked migration.
--
-- public.users held the only real row across all four tables: the admin
-- seed from V1. It is recreated here directly in auth_schema.users so it
-- becomes reachable by the application for the first time.

DROP TABLE IF EXISTS auth_schema.outbox_events;
DROP TABLE IF EXISTS auth_schema.users;
DROP TABLE IF EXISTS public.outbox_events;
DROP TABLE IF EXISTS public.users;

CREATE TABLE auth_schema.users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    email_verified BOOLEAN,
    email_verification_token VARCHAR(500),
    token_created_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    last_login_at TIMESTAMP,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT users_role_check CHECK (role IN ('BUYER', 'SELLER', 'ADMIN')),
    CONSTRAINT users_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETED'))
);

CREATE INDEX idx_users_role ON auth_schema.users(role);
CREATE INDEX idx_users_status ON auth_schema.users(status);
CREATE INDEX idx_users_verification_token ON auth_schema.users(email_verification_token);

INSERT INTO auth_schema.users (email, password_hash, first_name, last_name, role, status, email_verified)
VALUES (
    'admin@easydora.com',
    '$2a$12$pQrStUvWxYzAbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdef',
    'Admin',
    'System',
    'ADMIN',
    'ACTIVE',
    true
);

CREATE TABLE auth_schema.outbox_events (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP NULL
);

CREATE INDEX idx_outbox_events_unpublished ON auth_schema.outbox_events(created_at) WHERE published_at IS NULL;
