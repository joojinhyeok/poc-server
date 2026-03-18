-- KEYS[1] = exchange:leases:{exchange}
-- ARGV[1] = maxConcurrent
-- ARGV[2] = workerId
-- ARGV[3] = expireTime (epoch millis, now + 60000)
-- ARGV[4] = now (epoch millis)

-- Step 1: lazy cleanup — 만료된 lease 삭제
local all = redis.call('HGETALL', KEYS[1])
for i = 1, #all, 2 do
    if tonumber(all[i+1]) < tonumber(ARGV[4]) then
        redis.call('HDEL', KEYS[1], all[i])
    end
end

-- Step 2: 슬롯 확인
local currentCount = redis.call('HLEN', KEYS[1])
if currentCount >= tonumber(ARGV[1]) then
    return 0  -- 실패: 슬롯 없음
end

-- Step 3: lease 등록
redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])
return 1  -- 성공