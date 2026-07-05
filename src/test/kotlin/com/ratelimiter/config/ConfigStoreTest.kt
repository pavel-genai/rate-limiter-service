package com.ratelimiter.config

import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConfigStoreTest {

    private lateinit var redis: RedisCommands<String, String>
    private lateinit var configStore: ConfigStore

    @BeforeEach
    fun setup() {
        redis = mockk(relaxed = true)
        configStore = ConfigStore(redis)
    }

    @Test
    fun `save persists config to redis as hash`() {
        val config = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
            limit = 100,
            windowMs = 60_000
        )

        configStore.save(config)

        verify {
            redis.hset("rl:config:client-1:/api/data", mapOf(
                "algorithm" to "TOKEN_BUCKET",
                "limit" to "100",
                "window_ms" to "60000"
            ))
        }
    }

    @Test
    fun `save with sliding window log algorithm`() {
        val config = RateLimitConfig(
            clientId = "client-2",
            resource = "/api/logs",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_LOG,
            limit = 50,
            windowMs = 30_000
        )

        configStore.save(config)

        verify {
            redis.hset("rl:config:client-2:/api/logs", mapOf(
                "algorithm" to "SLIDING_WINDOW_LOG",
                "limit" to "50",
                "window_ms" to "30000"
            ))
        }
    }

    @Test
    fun `save with sliding window counter algorithm`() {
        val config = RateLimitConfig(
            clientId = "client-3",
            resource = "/api/counter",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
            limit = 200,
            windowMs = 120_000
        )

        configStore.save(config)

        verify {
            redis.hset("rl:config:client-3:/api/counter", mapOf(
                "algorithm" to "SLIDING_WINDOW_COUNTER",
                "limit" to "200",
                "window_ms" to "120000"
            ))
        }
    }

    @Test
    fun `get returns config when data exists in redis`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "algorithm" to "TOKEN_BUCKET",
            "limit" to "100",
            "window_ms" to "60000"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNotNull(result)
        assertEquals("client-1", result!!.clientId)
        assertEquals("/api/data", result.resource)
        assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, result.algorithm)
        assertEquals(100L, result.limit)
        assertEquals(60_000L, result.windowMs)
    }

    @Test
    fun `get returns config with sliding window log algorithm`() {
        every { redis.hgetall("rl:config:client-2:/api/logs") } returns mapOf(
            "algorithm" to "SLIDING_WINDOW_LOG",
            "limit" to "50",
            "window_ms" to "30000"
        )

        val result = configStore.get("client-2", "/api/logs")

        assertNotNull(result)
        assertEquals(RateLimitAlgorithm.SLIDING_WINDOW_LOG, result!!.algorithm)
        assertEquals(50L, result.limit)
        assertEquals(30_000L, result.windowMs)
    }

    @Test
    fun `get returns config with sliding window counter algorithm`() {
        every { redis.hgetall("rl:config:client-3:/api/counter") } returns mapOf(
            "algorithm" to "SLIDING_WINDOW_COUNTER",
            "limit" to "200",
            "window_ms" to "120000"
        )

        val result = configStore.get("client-3", "/api/counter")

        assertNotNull(result)
        assertEquals(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, result!!.algorithm)
    }

    @Test
    fun `get returns null when no data exists`() {
        every { redis.hgetall("rl:config:unknown:/api/missing") } returns emptyMap()

        val result = configStore.get("unknown", "/api/missing")

        assertNull(result)
    }

    @Test
    fun `get returns null when redis returns null map`() {
        every { redis.hgetall("rl:config:unknown:/api/null") } returns null

        val result = configStore.get("unknown", "/api/null")

        assertNull(result)
    }

    @Test
    fun `get returns null when algorithm field is invalid`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "algorithm" to "INVALID_ALGORITHM",
            "limit" to "100",
            "window_ms" to "60000"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNull(result)
    }

    @Test
    fun `get returns null when limit is not a valid number`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "algorithm" to "TOKEN_BUCKET",
            "limit" to "not-a-number",
            "window_ms" to "60000"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNull(result)
    }

    @Test
    fun `get returns null when window_ms is not a valid number`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "algorithm" to "TOKEN_BUCKET",
            "limit" to "100",
            "window_ms" to "invalid"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNull(result)
    }

    @Test
    fun `get returns null when algorithm field is missing`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "limit" to "100",
            "window_ms" to "60000"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNull(result)
    }

    @Test
    fun `get returns null when limit field is missing`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "algorithm" to "TOKEN_BUCKET",
            "window_ms" to "60000"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNull(result)
    }

    @Test
    fun `get returns null when window_ms field is missing`() {
        every { redis.hgetall("rl:config:client-1:/api/data") } returns mapOf(
            "algorithm" to "TOKEN_BUCKET",
            "limit" to "100"
        )

        val result = configStore.get("client-1", "/api/data")

        assertNull(result)
    }

    @Test
    fun `config key is constructed from clientId and resource`() {
        every { redis.hgetall("rl:config:my-client:/my/resource") } returns mapOf(
            "algorithm" to "TOKEN_BUCKET",
            "limit" to "10",
            "window_ms" to "5000"
        )

        configStore.get("my-client", "/my/resource")

        verify { redis.hgetall("rl:config:my-client:/my/resource") }
    }

    @Test
    fun `save and get round trip preserves all fields`() {
        val original = RateLimitConfig(
            clientId = "round-trip",
            resource = "/api/test",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_LOG,
            limit = 42,
            windowMs = 7777
        )

        val capturedMap = slot<Map<String, String>>()
        every { redis.hset(any<String>(), capture(capturedMap)) } returns 3L

        configStore.save(original)

        // Now simulate get with the captured values
        every { redis.hgetall("rl:config:round-trip:/api/test") } returns capturedMap.captured

        val retrieved = configStore.get("round-trip", "/api/test")

        assertNotNull(retrieved)
        assertEquals(original, retrieved)
    }
}
