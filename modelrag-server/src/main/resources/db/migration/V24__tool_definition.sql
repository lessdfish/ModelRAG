CREATE TABLE IF NOT EXISTS kb_tool_definition (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(1000),
    risk_level VARCHAR(20) NOT NULL DEFAULT 'LOW',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    endpoint TEXT,
    auth_header_name VARCHAR(100),
    auth_header_value TEXT,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);
