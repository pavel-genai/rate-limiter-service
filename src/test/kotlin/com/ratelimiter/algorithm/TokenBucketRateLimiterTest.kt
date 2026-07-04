package com.ratelimiter.algorithm

import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class TokenBucketRateLimiterTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        private lateinit var client: RedisClient
        private lateinit var commands: RedisCommands<String, String>

        @BeforeAll
        @JvmStatic
        fun setup() {
            client = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(6379)}")
            commands = client.connect().sync()
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            client.shutdown()
        }
    }

    private lateinit var limiter: TokenBucketRateLimiter

    @BeforeEach
    fun beforeEach() {
        commands.flushall()
        limiter = TokenBucketRateLimiter(commands)
    }

    private fun config(limit: Long = 5, windowMs: Long = 10_000) = RateLimitConfig(
        clientId = "test-client",
        resource = "/api/test",
        algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
        limit = limit,
        windowMs = windowMs
    )

    @Test
    fun `allows requests within limit`() {
        val cfg = config(limit = 3)
        val r1 = limiter.check(cfg)
        assertTrue(r1.allowed)
        assertEquals(2L, r1.remaining)

        val r2 = limiter.check(cfg)
        assertTrue(r2.allowed)
        assertEquals(1L, r2.remaining)

        val r3 = limiter.check(cfg)
        assertTrue(r3.allowed)
        assertEquals(0L, r3.remaining)
    }

    @Test
    fun `rejects requests exceeding limit`() {
        val cfg = config(limit = 2)
        limiter.check(cfg)
        limiter.check(cfg)

        val result = limiter.check(cfg)
        assertFalse(result.allowed)
        assertTrue(result.retryAfterMs > 0)
    }

    @Test
    fun `tokens refill over time`() {
        val cfg = config(limit = 2, windowMs = 1000)
        limiter.check(cfg)
        limiter.check(cfg)

        // Wait for partial refill
        Thread.sleep(600)

        val result = limiter.check(cfg)
        assertTrue(result.allowed, "Should be allowed after tokens refill")
    }

    @Test
    fun `remaining never exceeds limit`() {
        val cfg = config(limit = 5)
        val result = limiter.check(cfg)
        assertTrue(result.remaining <= cfg.limit)
    }

    @Test
    fun `different clients are independent`() {
        val cfg1 = config(limit = 1).copy(clientId = "client-a")
        val cfg2 = config(limit = 1).copy(clientId = "client-b")

        assertTrue(limiter.check(cfg1).allowed)
        assertFalse(limiter.check(cfg1).allowed)

        // Client B should still have quota
        assertTrue(limiter.check(cfg2).allowed)
    }
}
