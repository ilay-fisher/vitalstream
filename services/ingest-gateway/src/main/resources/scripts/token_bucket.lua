-- Token bucket executed atomically inside Redis.
-- KEYS[1] bucket key (rl:{deviceId}), a hash with fields tokens and ts (ms)
-- ARGV[1] refill rate in tokens per second
-- ARGV[2] burst capacity (maximum tokens)
-- ARGV[3] tokens requested
-- ARGV[4] key TTL in seconds (idle buckets disappear on their own)
-- Returns {allowed (1/0), retry_after_ms}.
-- Time comes from Redis (TIME), not from the caller, so gateway instances with skewed clocks
-- all see one consistent timeline.
local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = burst
  ts = now_ms
end

local elapsed = math.max(0, now_ms - ts)
tokens = math.min(burst, tokens + elapsed * rate / 1000)

local allowed = 0
local retry_ms = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  retry_ms = math.ceil((requested - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now_ms))
redis.call('EXPIRE', KEYS[1], ttl)
return {allowed, retry_ms}
