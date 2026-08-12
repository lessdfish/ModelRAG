CREATE TABLE IF NOT EXISTS kb_user_account (
    user_id VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200),
    password_hash VARCHAR(300) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kb_user_role (
    user_id VARCHAR(100) NOT NULL REFERENCES kb_user_account(user_id) ON DELETE CASCADE,
    role_name VARCHAR(40) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, role_name)
);

CREATE TABLE IF NOT EXISTS kb_dataset_acl (
    dataset_id BIGINT NOT NULL REFERENCES kb_dataset(id) ON DELETE CASCADE,
    user_id VARCHAR(100) NOT NULL REFERENCES kb_user_account(user_id) ON DELETE CASCADE,
    permission VARCHAR(20) NOT NULL DEFAULT 'READ',
    create_time TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (dataset_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_role_user ON kb_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_dataset_acl_user ON kb_dataset_acl(user_id, dataset_id);

INSERT INTO kb_user_account(user_id, display_name, password_hash, enabled)
VALUES ('admin', '本地管理员', '{sha256}35f37971c8c9f3fa7576ca638d03ae7f2edce9c1630923d2241e3d2545334adb', TRUE)
ON CONFLICT(user_id) DO NOTHING;

INSERT INTO kb_user_role(user_id, role_name)
VALUES ('admin', 'ADMIN'), ('admin', 'APPROVER')
ON CONFLICT(user_id, role_name) DO NOTHING;
