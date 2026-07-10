package com.ratelimiter.algorithm

import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TokenBucketRateLimiterUnitTest {

    private lateinit var redis: RedisCommands<String, String>
    private lateinit var limiter: TokenBucketRateLimiter

    @BeforeEach
    fun setup() {
        redis = mockk(relaxed = true)
        limiter = TokenBucketRateLimiter(redis)
    }

    private fun config(limit: Long = 5, windowMs: Long = 10_000) = RateLimitConfig(
        clientId = "test-client",
        resource = "/api/test",
        algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
        limit = limit,
        windowMs = windowMs
    )

    private fun mockEval(result: List<Long>) {
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns result
    }

    @Test
    fun `check returns allowed result when Redis returns 1`() {
        mockEval(listOf(1L, 4L, 0L))

        val result = limiter.check(config())
        assertTrue(result.allowed)
        assertEquals(4L, result.remaining)
        assertEquals(0L, result.retryAfterMs)
    }

    @Test
    fun `check returns denied result when Redis returns 0`() {
        mockEval(listOf(0L, 0L, 3000L))

        val result = limiter.check(config())
        assertFalse(result.allowed)
        assertEquals(0L, result.remaining)
        assertEquals(3000L, result.retryAfterMs)
    }

    @Test
    fun `check uses correct key format with clientId and resource`() {
        val arrSlot = slot<Array<String>>()
        every {
            redis.eval<List<Long>>(any<String>(), any(), capture(arrSlot), *anyVararg())
        } returns listOf(1L, 0L, 0L)

        limiter.check(config().copy(clientId = "my-client", resource = "/my/resource"))

        assertEquals("rl:tb:my-client:/my/resource", arrSlot.captured[0])
    }

    @Test
    fun `check handles large remaining value`() {
        mockEval(listOf(1L, 999999L, 0L))

        val result = limiter.check(config())
        assertEquals(999999L, result.remaining)
    }
}

class SlidingWindowLogRateLimiterUnitTest {

    private lateinit var redis: RedisCommands<String, String>
    private lateinit var limiter: SlidingWindowLogRateLimiter

    @BeforeEach
    fun setup() {
        redis = mockk(relaxed = true)
        limiter = SlidingWindowLogRateLimiter(redis)
    }

    private fun config(limit: Long = 5, windowMs: Long = 10_000) = RateLimitConfig(
        clientId = "test-client",
        resource = "/api/test",
        algorithm = RateLimitAlgorithm.SLIDING_WINDOW_LOG,
        limit = limit,
        windowMs = windowMs
    )

    private fun mockEval(result: List<Long>) {
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns result
    }

    @Test
    fun `check returns allowed result when Redis returns 1`() {
        mockEval(listOf(1L, 2L, 0L))

        val result = limiter.check(config())
        assertTrue(result.allowed)
        assertEquals(2L, result.remaining)
    }

    @Test
    fun `check returns denied result when Redis returns 0`() {
        mockEval(listOf(0L, 0L, 7000L))

        val result = limiter.check(config())
        assertFalse(result.allowed)
        assertEquals(7000L, result.retryAfterMs)
    }

    @Test
    fun `check uses correct key format`() {
        val arrSlot = slot<Array<String>>()
        every {
            redis.eval<List<Long>>(any<String>(), any(), capture(arrSlot), *anyVararg())
        } returns listOf(1L, 0L, 0L)

        limiter.check(config().copy(clientId = "swl-client", resource = "/swl/resource"))

        assertEquals("rl:swl:swl-client:/swl/resource", arrSlot.captured[0])
    }
}

class SlidingWindowCounterRateLimiterUnitTest {

    private lateinit var redis: RedisCommands<String, String>
    private lateinit var limiter: SlidingWindowCounterRateLimiter

    @BeforeEach
    fun setup() {
        redis = mockk(relaxed = true)
        limiter = SlidingWindowCounterRateLimiter(redis)
    }

    private fun config(limit: Long = 5, windowMs: Long = 10_000) = RateLimitConfig(
        clientId = "test-client",
        resource = "/api/test",
        algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
        limit = limit,
        windowMs = windowMs
    )

    private fun mockEval(result: List<Long>) {
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns result
    }

    @Test
    fun `check returns allowed result when Redis returns 1`() {
        mockEval(listOf(1L, 3L, 0L))

        val result = limiter.check(config())
        assertTrue(result.allowed)
        assertEquals(3L, result.remaining)
    }

    @Test
    fun `check returns denied result when Redis returns 0`() {
        mockEval(listOf(0L, 0L, 5000L))

        val result = limiter.check(config())
        assertFalse(result.allowed)
        assertEquals(5000L, result.retryAfterMs)
    }

    @Test
    fun `check uses correct key format`() {
        val arrSlot = slot<Array<String>>()
        every {
            redis.eval<List<Long>>(any<String>(), any(), capture(arrSlot), *anyVararg())
        } returns listOf(1L, 0L, 0L)

        limiter.check(config().copy(clientId = "swc-client", resource = "/swc/resource"))

        assertEquals("rl:swc:swc-client:/swc/resource", arrSlot.captured[0])
    }
}