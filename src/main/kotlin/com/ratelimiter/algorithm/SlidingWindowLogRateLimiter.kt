package com.ratelimiter.algorithm

import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.model.RateLimitResult
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands

/**
 * Sliding window log algorithm backed by Redis.
 *
 * Maintains a sorted set of request timestamps. On each check, removes
 * entries older than the window, then counts remaining entries. If the
 * count is below the limit, the request is allowed and the current
 * timestamp is added.
 */
class SlidingWindowLogRateLimiter(
    private val redis: RedisCommands<String, String>
) : RateLimiter {

    companion object {
        private val SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local window_start = now - window_ms

            -- Remove expired entries
            redis.call('ZREMRANGEBYSCORE', key, '-inf', tostring(window_start))

            local count = redis.call('ZCARD', key)
            local allowed = 0
            local remaining = limit - count
            local retry_after = 0

            if count < limit then
                -- Use now + a counter suffix to allow multiple requests at same ms
                redis.call('ZADD', key, tostring(now), tostring(now) .. ':' .. tostring(count))
                allowed = 1
                remaining = remaining - 1
            else
                -- Find the oldest entry to calculate retry_after
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                if #oldest >= 2 then
                    local oldest_ts = tonumber(oldest[2])
                    retry_after = math.max(0, math.ceil(oldest_ts + window_ms - now))
                end
            end

            redis.call('PEXPIRE', key, window_ms * 2)

            return {allowed, math.max(0, remaining), retry_after}
        """.trimIndent()
    }

    override fun check(config: RateLimitConfig): RateLimitResult {
        val key = "rl:swl:${config.clientId}:${config.resource}"
        val now = System.currentTimeMillis()

        val result = redis.eval<List<Long>>(
            SCRIPT,
            ScriptOutputType.MULTI,
            arrayOf(key),
            config.limit.toString(),
            config.windowMs.toString(),
            now.toString()
        )

        return RateLimitResult(
            allowed = result[0] == 1L,
            remaining = result[1],
            retryAfterMs = result[2]
        )
    }
}
