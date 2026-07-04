package com.ratelimiter.algorithm

import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.model.RateLimitResult

/**
 * Common interface for all rate-limiting algorithms.
 */
interface RateLimiter {
    /**
     * Check whether a request should be allowed under the given configuration.
     * Implementations must atomically record the request if allowed.
     */
    fun check(config: RateLimitConfig): RateLimitResult
}
