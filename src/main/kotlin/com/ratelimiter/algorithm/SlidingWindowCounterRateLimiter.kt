package com.ratelimiter.algorithm

import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.model.RateLimitResult
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands

/**
 * Sliding window counter algorithm backed by Redis.
 *
 * Divides time into fixed sub-windows. The effective count is calculated
 * as a weighted combination of the previous window's count and the current
 * window's count, based on how far into the current window we are.
 *
 * This provides a good approximation of a true sliding window with much
 * lower memory usage than the log approach.
 */
class SlidingWindowCounterRateLimiter(
    private val redis: RedisCommands<String, String>
) : RateLimiter {

    companion object {
        private val SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            -- Determine current and previous window boundaries
            local current_window = math.floor(now / window_ms)
            local window_offset = (now % window_ms) / window_ms  -- 0.0 to 1.0

            local prev_key = key .. ':' .. tostring(current_window - 1)
            local curr_key = key .. ':' .. tostring(current_window)

            local prev_count = tonumber(redis.call('GET', prev_key) or '0') or 0
            local curr_count = tonumber(redis.call('GET', curr_key) or '0') or 0

            -- Weighted count: previous window's contribution decreases as we
            -- move further into the current window
            local weighted = prev_count * (1 - window_offset) + curr_count

            local allowed = 0
            local remaining = math.max(0, math.floor(limit - weighted))
            local retry_after = 0

            if weighted < limit then
                redis.call('INCR', curr_key)
                -- Set expiry so keys are cleaned up after two full windows
                redis.call('PEXPIRE', curr_key, window_ms * 2)
                redis.call('PEXPIRE', prev_key, window_ms * 2)
                allowed = 1
                remaining = remaining - 1
            else
                -- Estimate when enough of the previous window will have "aged out"
                -- to allow the next request
                if prev_count > 0 then
                    local needed = weighted - limit + 1
                    local drain_rate = prev_count / window_ms
                    if drain_rate > 0 then
                        retry_after = math.ceil(needed / drain_rate)
                    else
                        retry_after = window_ms
                    end
                else
                    -- All requests are in the current window; must wait for window reset
                    retry_after = math.ceil(window_ms * (1 - window_offset))
                end
            end

            return {allowed, math.max(0, remaining), retry_after}
        """.trimIndent()
    }

    override fun check(config: RateLimitConfig): RateLimitResult {
        val key = "rl:swc:${config.clientId}:${config.resource}"
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
