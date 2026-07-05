package com.ratelimiter.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RateLimitConfigTest {

    @Test
    fun `data class properties are set correctly`() {
        val config = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
            limit = 100,
            windowMs = 60_000
        )

        assertEquals("client-1", config.clientId)
        assertEquals("/api/data", config.resource)
        assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, config.algorithm)
        assertEquals(100L, config.limit)
        assertEquals(60_000L, config.windowMs)
    }

    @Test
    fun `data class equality works correctly`() {
        val config1 = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
            limit = 100,
            windowMs = 60_000
        )
        val config2 = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
            limit = 100,
            windowMs = 60_000
        )

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `data class inequality on different clientId`() {
        val config1 = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)
        val config2 = RateLimitConfig("b", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)

        assertNotEquals(config1, config2)
    }

    @Test
    fun `data class inequality on different resource`() {
        val config1 = RateLimitConfig("a", "/api/1", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)
        val config2 = RateLimitConfig("a", "/api/2", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)

        assertNotEquals(config1, config2)
    }

    @Test
    fun `data class inequality on different algorithm`() {
        val config1 = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)
        val config2 = RateLimitConfig("a", "/api", RateLimitAlgorithm.SLIDING_WINDOW_LOG, 10, 1000)

        assertNotEquals(config1, config2)
    }

    @Test
    fun `data class inequality on different limit`() {
        val config1 = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)
        val config2 = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 20, 1000)

        assertNotEquals(config1, config2)
    }

    @Test
    fun `data class inequality on different windowMs`() {
        val config1 = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)
        val config2 = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 2000)

        assertNotEquals(config1, config2)
    }

    @Test
    fun `copy creates independent copy with modified field`() {
        val original = RateLimitConfig("a", "/api", RateLimitAlgorithm.TOKEN_BUCKET, 10, 1000)
        val copy = original.copy(limit = 20)

        assertEquals(10L, original.limit)
        assertEquals(20L, copy.limit)
        assertEquals(original.clientId, copy.clientId)
        assertEquals(original.resource, copy.resource)
        assertEquals(original.algorithm, copy.algorithm)
        assertEquals(original.windowMs, copy.windowMs)
    }

    @Test
    fun `toString contains all fields`() {
        val config = RateLimitConfig("client-1", "/api/data", RateLimitAlgorithm.TOKEN_BUCKET, 100, 60000)
        val str = config.toString()

        assertTrue(str.contains("client-1"))
        assertTrue(str.contains("/api/data"))
        assertTrue(str.contains("TOKEN_BUCKET"))
        assertTrue(str.contains("100"))
        assertTrue(str.contains("60000"))
    }
}

class RateLimitResultTest {

    @Test
    fun `allowed result has correct properties`() {
        val result = RateLimitResult(allowed = true, remaining = 5, retryAfterMs = 0)

        assertTrue(result.allowed)
        assertEquals(5L, result.remaining)
        assertEquals(0L, result.retryAfterMs)
    }

    @Test
    fun `denied result has correct properties`() {
        val result = RateLimitResult(allowed = false, remaining = 0, retryAfterMs = 5000)

        assertFalse(result.allowed)
        assertEquals(0L, result.remaining)
        assertEquals(5000L, result.retryAfterMs)
    }

    @Test
    fun `data class equality works correctly`() {
        val r1 = RateLimitResult(true, 5, 0)
        val r2 = RateLimitResult(true, 5, 0)

        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `data class inequality on different allowed`() {
        val r1 = RateLimitResult(true, 0, 0)
        val r2 = RateLimitResult(false, 0, 0)

        assertNotEquals(r1, r2)
    }

    @Test
    fun `data class inequality on different remaining`() {
        val r1 = RateLimitResult(true, 5, 0)
        val r2 = RateLimitResult(true, 3, 0)

        assertNotEquals(r1, r2)
    }

    @Test
    fun `data class inequality on different retryAfterMs`() {
        val r1 = RateLimitResult(false, 0, 1000)
        val r2 = RateLimitResult(false, 0, 2000)

        assertNotEquals(r1, r2)
    }

    @Test
    fun `copy creates independent copy with modified field`() {
        val original = RateLimitResult(true, 5, 0)
        val copy = original.copy(allowed = false, retryAfterMs = 3000)

        assertTrue(original.allowed)
        assertFalse(copy.allowed)
        assertEquals(5L, copy.remaining)
        assertEquals(3000L, copy.retryAfterMs)
    }

    @Test
    fun `toString contains all fields`() {
        val result = RateLimitResult(true, 5, 0)
        val str = result.toString()

        assertTrue(str.contains("true"))
        assertTrue(str.contains("5"))
        assertTrue(str.contains("0"))
    }
}

class RateLimitAlgorithmTest {

    @Test
    fun `enum has exactly three values`() {
        val values = RateLimitAlgorithm.values()
        assertEquals(3, values.size)
    }

    @Test
    fun `TOKEN_BUCKET exists`() {
        val algo = RateLimitAlgorithm.valueOf("TOKEN_BUCKET")
        assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, algo)
    }

    @Test
    fun `SLIDING_WINDOW_LOG exists`() {
        val algo = RateLimitAlgorithm.valueOf("SLIDING_WINDOW_LOG")
        assertEquals(RateLimitAlgorithm.SLIDING_WINDOW_LOG, algo)
    }

    @Test
    fun `SLIDING_WINDOW_COUNTER exists`() {
        val algo = RateLimitAlgorithm.valueOf("SLIDING_WINDOW_COUNTER")
        assertEquals(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, algo)
    }

    @Test
    fun `valueOf with invalid name throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            RateLimitAlgorithm.valueOf("INVALID")
        }
    }

    @Test
    fun `name returns correct string representation`() {
        assertEquals("TOKEN_BUCKET", RateLimitAlgorithm.TOKEN_BUCKET.name)
        assertEquals("SLIDING_WINDOW_LOG", RateLimitAlgorithm.SLIDING_WINDOW_LOG.name)
        assertEquals("SLIDING_WINDOW_COUNTER", RateLimitAlgorithm.SLIDING_WINDOW_COUNTER.name)
    }

    @Test
    fun `ordinal values are sequential`() {
        val values = RateLimitAlgorithm.values()
        for (i in values.indices) {
            assertEquals(i, values[i].ordinal)
        }
    }
}
