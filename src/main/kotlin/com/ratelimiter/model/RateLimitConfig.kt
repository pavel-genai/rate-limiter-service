package com.ratelimiter.model

/**
 * Configuration for a rate limit rule applied to a specific client and resource.
 *
 * @param clientId Unique identifier for the client.
 * @param resource The resource being rate-limited (e.g., "/api/v1/users").
 * @param algorithm Which rate-limiting algorithm to apply.
 * @param limit Maximum number of requests allowed in the window.
 * @param windowMs Duration of the rate-limit window in milliseconds.
 */
data class RateLimitConfig(
    val clientId: String,
    val resource: String,
    val algorithm: RateLimitAlgorithm,
    val limit: Long,
    val windowMs: Long
)

enum class RateLimitAlgorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER
}

/**
 * Result of a rate-limit check.
 *
 * @param allowed Whether the request is permitted.
 * @param remaining Approximate number of requests remaining in the current window.
 * @param retryAfterMs Milliseconds to wait before retrying (0 if allowed).
 */
data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Long,
    val retryAfterMs: Long
)
