-- collection_jobs에 실패 심볼 카운터 추가
ALTER TABLE collection_jobs
    ADD COLUMN IF NOT EXISTS failed_symbols INTEGER NOT NULL DEFAULT 0;

-- 심볼별 수집 실패 기록
CREATE TABLE IF NOT EXISTS collection_failures (
    id         BIGSERIAL    PRIMARY KEY,
    job_id     BIGINT       NOT NULL REFERENCES collection_jobs(id),
    symbol     VARCHAR(50)  NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    retried    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cf_job_id ON collection_failures(job_id);
