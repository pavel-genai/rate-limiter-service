package com.ratelimiter.config

import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

/**
 * Persists and retrieves per-client rate-limit configurations in Redis.
 */
class ConfigStore(private val redis: RedisCommands<String, String>) {

    private val logger = LoggerFactory.getLogger(ConfigStore::class.java)

    fun save(config: RateLimitConfig) {
        val key = configKey(config.clientId, config.resource)
        redis.hset(key, mapOf(
            "algorithm" to config.algorithm.name,
            "limit" to config.limit.toString(),
            "window_ms" to config.windowMs.toString()
        ))
        logger.info("Saved config for {}:{} -> {} (limit={}, window={}ms)",
            config.clientId, config.resource, config.algorithm, config.limit, config.windowMs)
    }

    fun get(clientId: String, resource: String): RateLimitConfig? {
        val key = configKey(clientId, resource)
        val data = redis.hgetall(key)
        if (data.isNullOrEmpty()) return null

        return try {
            RateLimitConfig(
                clientId = clientId,
                resource = resource,
                algorithm = RateLimitAlgorithm.valueOf(data["algorithm"]!!),
                limit = data["limit"]!!.toLong(),
                windowMs = data["window_ms"]!!.toLong()
            )
        } catch (e: Exception) {
            logger.error("Failed to parse config for {}:{}", clientId, resource, e)
            null
        }
    }

    private fun configKey(clientId: String, resource: String) =
        "rl:config:$clientId:$resource"
}
