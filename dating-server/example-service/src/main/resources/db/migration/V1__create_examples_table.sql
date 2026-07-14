-- V1__create_examples_table.sql
-- Create examples table for example service

CREATE TABLE IF NOT EXISTS examples (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted SMALLINT NOT NULL DEFAULT 0
);

-- Create index on business ID if needed
CREATE INDEX idx_examples_name ON examples(name);

-- Create index on created_at for time-based queries
CREATE INDEX idx_examples_created_at ON examples(created_at);
