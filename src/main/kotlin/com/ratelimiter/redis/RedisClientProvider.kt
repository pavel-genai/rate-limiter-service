package com.ratelimiter.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

/**
 * Manages the Lettuce Redis connection lifecycle.
 */
class RedisClientProvider(
    private val host: String = System.getenv("REDIS_HOST") ?: "localhost",
    private val port: Int = (System.getenv("REDIS_PORT") ?: "6379").toInt()
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(RedisClientProvider::class.java)
    private val client: RedisClient
    private val connection: StatefulRedisConnection<String, String>

    init {
        val uri = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .build()
        client = RedisClient.create(uri)
        connection = client.connect()
        logger.info("Connected to Redis at {}:{}", host, port)
    }

    fun sync(): RedisCommands<String, String> = connection.sync()

    override fun close() {
        connection.close()
        client.shutdown()
        logger.info("Redis connection closed")
    }
}
