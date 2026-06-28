-- ============================================================
-- V1: Initial schema for UPI Offline Mesh
-- Tables: accounts, transactions
-- ============================================================

CREATE TABLE IF NOT EXISTS accounts (
    vpa          VARCHAR(255) PRIMARY KEY,
    holder_name  VARCHAR(255) NOT NULL,
    balance      NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
    id             BIGSERIAL PRIMARY KEY,
    packet_hash    VARCHAR(64)    NOT NULL,
    sender_vpa     VARCHAR(255)   NOT NULL,
    receiver_vpa   VARCHAR(255)   NOT NULL,
    amount         NUMERIC(19,2)  NOT NULL,
    signed_at      TIMESTAMPTZ    NOT NULL,
    settled_at     TIMESTAMPTZ    NOT NULL,
    bridge_node_id VARCHAR(255)   NOT NULL,
    hop_count      INT            NOT NULL DEFAULT 0,
    status         VARCHAR(20)    NOT NULL,

    CONSTRAINT uq_packet_hash UNIQUE (packet_hash)
);

CREATE INDEX IF NOT EXISTS idx_tx_sender   ON transactions(sender_vpa);
CREATE INDEX IF NOT EXISTS idx_tx_receiver ON transactions(receiver_vpa);
CREATE INDEX IF NOT EXISTS idx_tx_status   ON transactions(status);

-- Seed demo accounts
INSERT INTO accounts (vpa, holder_name, balance, version) VALUES
    ('alice@demo', 'Alice', 5000.00, 0),
    ('bob@demo',   'Bob',   1000.00, 0),
    ('carol@demo', 'Carol', 2500.00, 0),
    ('dave@demo',  'Dave',   500.00, 0)
ON CONFLICT (vpa) DO NOTHING;
