-- 수집 작업
CREATE TABLE IF NOT EXISTS collection_jobs (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id),
    exchange          VARCHAR(20)  NOT NULL,
    type              VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    total_symbols     INTEGER      NOT NULL DEFAULT 0,
    processed_symbols INTEGER      NOT NULL DEFAULT 0,
    new_trades_count  INTEGER      NOT NULL DEFAULT 0,
    fail_reason       TEXT,
    started_at        TIMESTAMP,
    completed_at      TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cj_user_exchange_status ON collection_jobs(user_id, exchange, status);

-- 동기화 커서
CREATE TABLE IF NOT EXISTS sync_cursors (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users(id),
    exchange       VARCHAR(20)  NOT NULL,
    symbol         VARCHAR(30)  NOT NULL,
    last_trade_id  VARCHAR(255) NOT NULL,
    last_synced_at TIMESTAMP    NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, exchange, symbol)
);