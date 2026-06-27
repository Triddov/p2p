-- ============================================================================
-- P2P Messenger Database Schema (Hybrid Model)
-- PostgreSQL 14+
-- v1 здесь много проблем
-- ============================================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- just for uuid_generate_v4() 
CREATE EXTENSION IF NOT EXISTS "pgcrypto";  --  

-- ============================================================================
-- 1. TABLE USERS
-- ============================================================================

CREATE TABLE users (
    -- Identification
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(32) UNIQUE DEFAULT NULL,

    -- Cryptography (only public keys!)
    identity_public_key BYTEA NOT NULL,

    -- Signal Protocol registration ID (assigned on first key upload)
    registration_id INT,

    -- metadata
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    last_seen TIMESTAMPTZ,
    online BOOLEAN DEFAULT FALSE,

    discoverable BOOLEAN NOT NULL DEFAULT TRUE,

    -- Device ?
    device_id VARCHAR(64),

    -- Constraints
    CONSTRAINT username_format CHECK (username ~ '^[a-z0-9_]{3,32}$')
);

-- Indexes
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username_prefix ON users (LOWER(username) text_pattern_ops) WHERE username IS NOT NULL;
CREATE INDEX idx_users_online ON users(last_seen DESC) WHERE online = TRUE;

-- comments
COMMENT ON TABLE users IS 'Messenger users. The server stores ONLY public keys.';
COMMENT ON COLUMN users.identity_public_key IS 'Ed25519 public key (32 bytes). The private key exists ONLY on the device.';
COMMENT ON COLUMN users.username IS 'Public username used for search.';


-- NOTE: Email verification codes live in Redis (key "sms_code:{email}", TTL 5 min).
-- No DB table needed.


-- ============================================================================
-- 2. SIGNAL PROTOCOL PREKEYS
-- ============================================================================

-- One active signed prekey per user (rotated periodically).
CREATE TABLE signed_prekeys (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    prekey_id  INT NOT NULL,
    public_key BYTEA NOT NULL,
    signature  BYTEA NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

-- Pool of one-time prekeys. Each is consumed exactly once by the first-message initiator.
CREATE TABLE one_time_prekeys (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    prekey_id  INT NOT NULL,
    public_key BYTEA NOT NULL,
    UNIQUE (user_id, prekey_id)
);

CREATE INDEX idx_otk_user ON one_time_prekeys(user_id);


-- ============================================================================
-- 3. PENDING MESSAGES (encrypted messages for offline delivery)
-- ============================================================================

CREATE TABLE pending_messages (
    -- Identification
    id UUID PRIMARY KEY,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Crypted blob (E2EE)
    ciphertext BYTEA NOT NULL,

    -- The cryptographic context
    ratchet_index BIGINT NOT NULL DEFAULT 0,

    -- Initial message for first contact (X3DH)
    initial_message JSONB,

    -- Signal Protocol message type: 2 = WHISPER (Double Ratchet), 3 = PREKEY (X3DH + first ratchet)
    message_type SMALLINT NOT NULL DEFAULT 2,

    -- Metadata
    timestamp TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    delivered BOOLEAN DEFAULT FALSE,
    delivered_at TIMESTAMPTZ,

    -- Constraints
    CONSTRAINT different_users CHECK (sender_id != recipient_id),
    CONSTRAINT initial_message_structure CHECK (
        initial_message IS NULL OR (
            initial_message ? 'sender_identity_key' AND
            initial_message ? 'sender_ephemeral_key'
        )
    )
);

-- Indexes
CREATE INDEX idx_pending_recipient ON pending_messages(recipient_id, delivered)
    WHERE delivered = FALSE;
CREATE INDEX idx_pending_timestamp ON pending_messages(timestamp DESC);
CREATE INDEX idx_pending_sender ON pending_messages(sender_id);

-- Составной индекс для выборки чата
CREATE INDEX idx_pending_chat ON pending_messages(sender_id, recipient_id, timestamp);

COMMENT ON TABLE pending_messages IS 'Messages for offline delivery. The server CANNOT decrypt them (E2EE).';
COMMENT ON COLUMN pending_messages.ciphertext IS 'AES-256-GCM encrypted message. The key is known only to the sender and recipient.';
COMMENT ON COLUMN pending_messages.ratchet_index IS 'Counter used for deriving a unique key for each message (forward secrecy).';
COMMENT ON COLUMN pending_messages.initial_message IS 'For the first message: contains public keys for ECDH. NULL for subsequent messages.';


-- ============================================================================
-- 4. DELIVERY RECEIPTS (квитанции о доставке)
-- ============================================================================

CREATE TABLE delivery_receipts (
    message_id UUID PRIMARY KEY REFERENCES pending_messages(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delivered_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    read_at TIMESTAMPTZ
);

CREATE INDEX idx_receipts_recipient ON delivery_receipts(recipient_id);

COMMENT ON TABLE delivery_receipts IS 'Receipts for the delivery of messages. Optional: read_at for "read"';


-- ============================================================================
-- 5. SIGNALING SESSIONS (active WebSocket connections)
-- ============================================================================

CREATE TABLE signaling_sessions (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    connection_id VARCHAR(64) UNIQUE NOT NULL,
    connected_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    last_ping TIMESTAMPTZ DEFAULT NOW() NOT NULL,

    -- signaling if than one server
    server_node VARCHAR(64) DEFAULT '1'
);

CREATE INDEX idx_signaling_node ON signaling_sessions(server_node);
CREATE INDEX idx_signaling_last_ping ON signaling_sessions(last_ping);

COMMENT ON TABLE signaling_sessions IS 'active WebSocket connections for signaling. Purge when disconnected.';


-- ============================================================================
-- 6. RATE LIMITING (spam protection)
-- ============================================================================

CREATE TABLE rate_limits (
    key VARCHAR(255) PRIMARY KEY,
    request_count INT DEFAULT 1,
    window_start TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    blocked_until TIMESTAMPTZ
);

CREATE INDEX idx_rate_limits_window ON rate_limits(window_start);

COMMENT ON TABLE rate_limits IS 'Rate limiting for API endpoints. Key = "email:{email}" or "ip:{ip}".';


-- ============================================================================
-- 7. AUDIT LOG (Optional)
-- ============================================================================

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50),
    resource_id VARCHAR(255),
    ip_address INET,
    user_agent TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_audit_user ON audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_action ON audit_log(action, created_at DESC);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);

COMMENT ON TABLE audit_log IS 'Log users actions. For security and debugging reasons.';


-- ============================================================================
-- FUNCSIONS AND TRIGGERS
-- ============================================================================

-- func: automatic update last_seen when connect
CREATE OR REPLACE FUNCTION update_user_last_seen()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE users
    SET last_seen = NOW(), online = TRUE
    WHERE id = NEW.user_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_signaling_connected
AFTER INSERT ON signaling_sessions
FOR EACH ROW
EXECUTE FUNCTION update_user_last_seen();


-- func: mark user offline when disconnect
CREATE OR REPLACE FUNCTION mark_user_offline()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE users
    SET online = FALSE
    WHERE id = OLD.user_id;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_signaling_disconnected
AFTER DELETE ON signaling_sessions
FOR EACH ROW
EXECUTE FUNCTION mark_user_offline();



-- fucn: autodelete delivered messages older than 30 days
CREATE OR REPLACE FUNCTION cleanup_old_delivered_messages()
RETURNS void AS $$
BEGIN
    DELETE FROM pending_messages
    WHERE delivered = TRUE
      AND delivered_at < NOW() - INTERVAL '30 days';
END;
$$ LANGUAGE plpgsql;

-- !!!
-- func: архивация старых audit логов (опционально)
CREATE OR REPLACE FUNCTION archive_old_audit_logs()
RETURNS void AS $$
BEGIN
    -- Переместить в архивную таблицу или удалить
    DELETE FROM audit_log
    WHERE created_at < NOW() - INTERVAL '90 days';
END;
$$ LANGUAGE plpgsql;


-- ============================================================================
-- SCHEDULED JOBS (через pg_cron или внешний cron)
-- ============================================================================

-- CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Очистка старых сообщений каждую ночь в 3:00
-- SELECT cron.schedule('cleanup-old-messages', '0 3 * * *', 'SELECT cleanup_old_delivered_messages()');

-- Очистка audit логов каждую неделю
-- SELECT cron.schedule('archive-audit-logs', '0 4 * * 0', 'SELECT archive_old_audit_logs()');


-- ============================================================================
-- VIEWS
-- ============================================================================

-- Active users (online last five minutes)
CREATE VIEW active_users AS
SELECT
    id,
    email,
    username,
    last_seen,
    online
FROM users
WHERE last_seen > NOW() - INTERVAL '5 minutes';


-- Statistic delivered messages
CREATE VIEW message_delivery_stats AS
SELECT
    sender_id,
    recipient_id,
    COUNT(*) as total_messages,
    COUNT(*) FILTER (WHERE delivered = TRUE) as delivered_count,
    COUNT(*) FILTER (WHERE delivered = FALSE) as pending_count,
    AVG(EXTRACT(EPOCH FROM (delivered_at - timestamp))) FILTER (WHERE delivered = TRUE) as avg_delivery_time_seconds
FROM pending_messages
GROUP BY sender_id, recipient_id;


-- ============================================================================
-- PARTITIONING 
-- ============================================================================

/*
CREATE TABLE pending_messages_2024_01 PARTITION OF pending_messages
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE pending_messages_2024_02 PARTITION OF pending_messages
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

-- etc
*/


-- ============================================================================
-- ACCESS RIGHTS
-- ============================================================================

-- The application role (messenger_backend) is created by the postgres container
-- via the POSTGRES_USER env variable — no need to CREATE ROLE here.
-- Grants below take effect on first init when the DB is empty.

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO CURRENT_USER;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO CURRENT_USER;



-- ============================================================================
-- ПОЛНОТЕКСТОВЫЙ ПОИСК (todo)
-- ============================================================================

-- username search:
-- CREATE INDEX idx_username_trgm ON users USING gin(username gin_trgm_ops);


-- ============================================================================
-- FINALLY
-- ============================================================================

-- vacuum for optimization
-- VACUUM ANALYZE;

COMMENT ON DATABASE p2p_db IS 'P2P Messenger (Hybrid Model): E2EE messages, server does not store private keys';
