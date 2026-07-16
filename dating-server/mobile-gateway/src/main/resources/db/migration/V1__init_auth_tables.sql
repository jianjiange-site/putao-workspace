-- Create auth_device table
CREATE TABLE IF NOT EXISTS auth_device (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    platform INTEGER NOT NULL,
    device_model VARCHAR(128),
    os_version VARCHAR(32),
    app_version VARCHAR(32),
    push_token VARCHAR(512),
    login_count INTEGER DEFAULT 1,
    last_login_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted INTEGER DEFAULT 0
);

-- Create index for user_id + device_id
CREATE INDEX IF NOT EXISTS idx_auth_device_user_device ON auth_device(user_id, device_id);

-- Create index for device_id
CREATE INDEX IF NOT EXISTS idx_auth_device_device ON auth_device(device_id);

COMMENT ON TABLE auth_device IS 'Device authentication records';
COMMENT ON COLUMN auth_device.user_id IS 'User ID';
COMMENT ON COLUMN auth_device.device_id IS 'Device unique identifier';
COMMENT ON COLUMN auth_device.platform IS 'Platform: 1=iOS, 2=Android, 3=Web';
COMMENT ON COLUMN auth_device.push_token IS 'Push notification token';

-- Create auth_refresh_token table
CREATE TABLE IF NOT EXISTS auth_refresh_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    jti VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted INTEGER DEFAULT 0
);

-- Create index for token_hash
CREATE INDEX IF NOT EXISTS idx_auth_refresh_token_hash ON auth_refresh_token(token_hash);

-- Create index for user_id
CREATE INDEX IF NOT EXISTS idx_auth_refresh_token_user ON auth_refresh_token(user_id);

-- Create index for jti
CREATE INDEX IF NOT EXISTS idx_auth_refresh_token_jti ON auth_refresh_token(jti);

COMMENT ON TABLE auth_refresh_token IS 'Refresh token records for token rotation';
COMMENT ON COLUMN auth_refresh_token.token_hash IS 'SHA-256 hash of refresh token';
COMMENT ON COLUMN auth_refresh_token.jti IS 'JWT ID for token tracking';
