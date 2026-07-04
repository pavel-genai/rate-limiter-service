package com.ratelimiter.algorithm

import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.model.RateLimitResult
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands

/**
 * Token bucket algorithm backed by Redis.
 *
 * Tokens refill at a constant rate (limit / windowMs). Each request consumes
 * one token. If no tokens remain, the request is rejected and retry_after
 * indicates how long until a token becomes available.
 *
 * All state is kept atomically in Redis via a Lua script.
 */
class TokenBucketRateLimiter(
    private val redis: RedisCommands<String, String>
) : RateLimiter {

    companion object {
        /**
         * Lua script that atomically:
         *  1. Reads current tokens and last-refill timestamp.
         *  2. Calculates tokens to add based on elapsed time.
         *  3. Consumes one token if available.
         *  4. Returns [allowed (0/1), remaining, retryAfterMs].
         */
        private val SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local refill_rate = limit / window_ms  -- tokens per ms

            local data = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(data[1])
            local last_refill = tonumber(data[2])

            if tokens == nil then
                tokens = limit
                last_refill = now
            end

            local elapsed = math.max(0, now - last_refill)
            local new_tokens = elapsed * refill_rate
            tokens = math.min(limit, tokens + new_tokens)
            last_refill = now

            local allowed = 0
            local retry_after = 0

            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retry_after = math.ceil((1 - tokens) / refill_rate)
            end

            redis.call('HMSET', key, 'tokens', tostring(tokens), 'last_refill', tostring(last_refill))
            redis.call('PEXPIRE', key, window_ms * 2)

            return {allowed, math.floor(tokens), retry_after}
        """.trimIndent()
    }

    override fun check(config: RateLimitConfig): RateLimitResult {
        val key = "rl:tb:${config.clientId}:${config.resource}"
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
