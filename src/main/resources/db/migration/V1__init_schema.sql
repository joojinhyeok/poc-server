-- users 테이블 (FK 참조용)
CREATE TABLE IF NOT EXISTS users (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255),
    nickname   VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 거래소 API Key
CREATE TABLE IF NOT EXISTS exchange_api_keys (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    exchange   VARCHAR(20)  NOT NULL,
    access_key VARCHAR(500) NOT NULL,
    secret_key VARCHAR(500) NOT NULL,
    is_valid   BOOLEAN      NOT NULL DEFAULT TRUE,
    memo       VARCHAR(255),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, exchange)
);