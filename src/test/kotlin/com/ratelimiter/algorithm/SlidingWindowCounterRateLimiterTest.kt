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
class SlidingWindowCounterRateLimiterTest {

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

    private lateinit var limiter: SlidingWindowCounterRateLimiter

    @BeforeEach
    fun beforeEach() {
        commands.flushall()
        limiter = SlidingWindowCounterRateLimiter(commands)
    }

    private fun config(limit: Long = 5, windowMs: Long = 10_000) = RateLimitConfig(
        clientId = "test-client",
        resource = "/api/test",
        algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
        limit = limit,
        windowMs = windowMs
    )

    @Test
    fun `allows requests within limit`() {
        val cfg = config(limit = 3)
        val r1 = limiter.check(cfg)
        assertTrue(r1.allowed)

        val r2 = limiter.check(cfg)
        assertTrue(r2.allowed)

        val r3 = limiter.check(cfg)
        assertTrue(r3.allowed)
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
    fun `allows requests after window passes`() {
        val cfg = config(limit = 2, windowMs = 1000)
        limiter.check(cfg)
        limiter.check(cfg)

        assertFalse(limiter.check(cfg).allowed)

        // Wait for more than two full windows so the previous window counter
        // is fully expired and has no weighted contribution
        Thread.sleep(2100)

        val result = limiter.check(cfg)
        assertTrue(result.allowed, "Should be allowed after window passes")
    }

    @Test
    fun `remaining count decreases`() {
        val cfg = config(limit = 3)
        val r1 = limiter.check(cfg)
        assertTrue(r1.remaining <= 2)

        val r2 = limiter.check(cfg)
        assertTrue(r2.remaining <= 1)

        val r3 = limiter.check(cfg)
        assertTrue(r3.remaining <= 0)
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

    @Test
    fun `different resources are independent`() {
        val cfg1 = config(limit = 1).copy(resource = "/api/resource-a")
        val cfg2 = config(limit = 1).copy(resource = "/api/resource-b")

        assertTrue(limiter.check(cfg1).allowed)
        assertFalse(limiter.check(cfg1).allowed)

        assertTrue(limiter.check(cfg2).allowed)
    }
}
