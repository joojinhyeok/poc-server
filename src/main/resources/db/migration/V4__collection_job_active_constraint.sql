-- 같은 user+exchange에 PENDING/PROCESSING Job이 동시에 2개 이상 생기는 것을 DB 레벨에서 방지
CREATE UNIQUE INDEX uk_cj_active_per_user_exchange
    ON collection_jobs (user_id, exchange)
    WHERE status IN ('PENDING', 'PROCESSING');
