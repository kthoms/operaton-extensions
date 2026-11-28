-- Business database, separate from the engine database ("operaton").
-- Runs once when the PostgreSQL container is first created.
CREATE DATABASE business;
\connect business

CREATE TABLE customer (
    id           VARCHAR(20)    PRIMARY KEY,
    name         VARCHAR(100)   NOT NULL,
    credit_limit NUMERIC(12, 2) NOT NULL,
    active       BOOLEAN        NOT NULL
);

CREATE TABLE sales_order (
    id          BIGSERIAL      PRIMARY KEY,
    customer_id VARCHAR(20)    NOT NULL REFERENCES customer (id),
    total       NUMERIC(12, 2) NOT NULL,
    placed_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);

INSERT INTO customer (id, name, credit_limit, active) VALUES
    ('C-1000', 'Alice Anderson', 5000.00, TRUE),
    ('C-2000', 'Bob Baker', 5000.00, FALSE),
    ('C-3000', 'Carol Clark', 100.00, TRUE);
