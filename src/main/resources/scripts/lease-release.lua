-- KEYS[1] = exchange:leases:{exchange}
-- ARGV[1] = workerId

-- 본인 lease만 삭제 (멱등)
redis.call('HDEL', KEYS[1], ARGV[1])
return 1