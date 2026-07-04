package com.ratelimiter.grpc

import com.ratelimiter.algorithm.RateLimiter
import com.ratelimiter.algorithm.SlidingWindowCounterRateLimiter
import com.ratelimiter.algorithm.SlidingWindowLogRateLimiter
import com.ratelimiter.algorithm.TokenBucketRateLimiter
import com.ratelimiter.config.ConfigStore
import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.proto.*
import io.grpc.Status
import io.grpc.StatusException
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

/**
 * gRPC service implementation that routes rate-limit checks to the
 * algorithm configured for each client/resource pair.
 */
class RateLimiterGrpcService(
    private val configStore: ConfigStore,
    redis: RedisCommands<String, String>
) : RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(RateLimiterGrpcService::class.java)

    private val limiters: Map<RateLimitAlgorithm, RateLimiter> = mapOf(
        RateLimitAlgorithm.TOKEN_BUCKET to TokenBucketRateLimiter(redis),
        RateLimitAlgorithm.SLIDING_WINDOW_LOG to SlidingWindowLogRateLimiter(redis),
        RateLimitAlgorithm.SLIDING_WINDOW_COUNTER to SlidingWindowCounterRateLimiter(redis)
    )

    override suspend fun checkRateLimit(request: CheckRateLimitRequest): CheckRateLimitResponse {
        if (request.clientId.isBlank() || request.resource.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("client_id and resource must not be empty"))
        }

        val config = configStore.get(request.clientId, request.resource)
            ?: throw StatusException(
                Status.NOT_FOUND.withDescription(
                    "No rate limit configured for client='${request.clientId}', resource='${request.resource}'"
                )
            )

        val limiter = limiters[config.algorithm]
            ?: throw StatusException(Status.INTERNAL.withDescription("Unknown algorithm: ${config.algorithm}"))

        val result = limiter.check(config)

        logger.debug("CheckRateLimit {}:{} -> allowed={}, remaining={}, retryAfter={}ms",
            request.clientId, request.resource, result.allowed, result.remaining, result.retryAfterMs)

        return checkRateLimitResponse {
            allowed = result.allowed
            remaining = result.remaining
            retryAfterMs = result.retryAfterMs
        }
    }

    override suspend fun configureLimit(request: ConfigureLimitRequest): ConfigureLimitResponse {
        if (request.clientId.isBlank() || request.resource.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("client_id and resource must not be empty"))
        }
        if (request.limit <= 0) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("limit must be positive"))
        }
        if (request.windowMs <= 0) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("window_ms must be positive"))
        }

        val algorithm = when (request.algorithm) {
            Algorithm.TOKEN_BUCKET -> RateLimitAlgorithm.TOKEN_BUCKET
            Algorithm.SLIDING_WINDOW_LOG -> RateLimitAlgorithm.SLIDING_WINDOW_LOG
            Algorithm.SLIDING_WINDOW_COUNTER -> RateLimitAlgorithm.SLIDING_WINDOW_COUNTER
            else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("Unknown algorithm"))
        }

        val config = RateLimitConfig(
            clientId = request.clientId,
            resource = request.resource,
            algorithm = algorithm,
            limit = request.limit,
            windowMs = request.windowMs
        )

        configStore.save(config)

        return configureLimitResponse {
            success = true
            message = "Rate limit configured: ${config.algorithm} (limit=${config.limit}, window=${config.windowMs}ms)"
        }
    }
}
