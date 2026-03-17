-- 거래 내역
CREATE TABLE IF NOT EXISTS trades (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL REFERENCES users(id),
    exchange          VARCHAR(20)     NOT NULL,
    exchange_trade_id VARCHAR(255)    NOT NULL,
    symbol            VARCHAR(30)     NOT NULL,
    side              VARCHAR(10)     NOT NULL,
    price             NUMERIC(30,15)  NOT NULL,
    quantity          NUMERIC(30,15)  NOT NULL,
    fee               NUMERIC(30,15),
    fee_currency      VARCHAR(10),
    traded_at         TIMESTAMP       NOT NULL,
    market            VARCHAR(30),
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_trades_idempotent UNIQUE (user_id, exchange, exchange_trade_id)
);

CREATE INDEX idx_trades_user_exchange ON trades(user_id, exchange);
CREATE INDEX idx_trades_user_symbol ON trades(user_id, exchange, symbol);

-- 자산 잔고
CREATE TABLE IF NOT EXISTS assets (
    id             BIGSERIAL       PRIMARY KEY,
    user_id        BIGINT          NOT NULL REFERENCES users(id),
    exchange       VARCHAR(20)     NOT NULL,
    coin           VARCHAR(20)     NOT NULL,
    amount         NUMERIC(30,15)  NOT NULL,
    avg_buy_price  NUMERIC(30,15),
    locked_amount  NUMERIC(30,15),
    created_at     TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_assets_upsert UNIQUE (user_id, exchange, coin)
);