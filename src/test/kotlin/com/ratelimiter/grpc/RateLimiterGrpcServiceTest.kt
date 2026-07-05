package com.ratelimiter.grpc

import com.ratelimiter.config.ConfigStore
import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.proto.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.StatusException
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class RateLimiterGrpcServiceTest {

    private lateinit var redis: RedisCommands<String, String>
    private lateinit var configStore: ConfigStore
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub

    @BeforeEach
    fun setup() {
        redis = mockk(relaxed = true)
        configStore = mockk(relaxed = true)

        val service = RateLimiterGrpcService(configStore, redis)

        server = ServerBuilder.forPort(0)
            .addService(service)
            .build()
            .start()

        val port = server.port
        channel = ManagedChannelBuilder.forAddress("localhost", port)
            .usePlaintext()
            .build()

        stub = RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub(channel)
    }

    @AfterEach
    fun teardown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    // --- checkRateLimit tests ---

    @Test
    fun `checkRateLimit with blank clientId throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.checkRateLimit(checkRateLimitRequest {
                    clientId = ""
                    resource = "/api/test"
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("client_id"))
    }

    @Test
    fun `checkRateLimit with blank resource throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.checkRateLimit(checkRateLimitRequest {
                    clientId = "client-1"
                    resource = ""
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("resource"))
    }

    @Test
    fun `checkRateLimit with both blank fields throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.checkRateLimit(checkRateLimitRequest {
                    clientId = ""
                    resource = ""
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }

    @Test
    fun `checkRateLimit with whitespace-only clientId throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.checkRateLimit(checkRateLimitRequest {
                    clientId = "   "
                    resource = "/api/test"
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }

    @Test
    fun `checkRateLimit with no config returns NOT_FOUND`() {
        every { configStore.get("unknown", "/api/missing") } returns null

        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.checkRateLimit(checkRateLimitRequest {
                    clientId = "unknown"
                    resource = "/api/missing"
                })
            }
        }
        assertEquals(io.grpc.Status.NOT_FOUND.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("unknown"))
        assertTrue(ex.status.description!!.contains("/api/missing"))
    }

    @Test
    fun `checkRateLimit returns allowed response when token bucket limiter allows`() = runBlocking {
        val config = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.TOKEN_BUCKET,
            limit = 10,
            windowMs = 60_000
        )
        every { configStore.get("client-1", "/api/data") } returns config
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns listOf(1L, 9L, 0L)

        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "client-1"
            resource = "/api/data"
        })

        assertTrue(response.allowed)
        assertEquals(9L, response.remaining)
        assertEquals(0L, response.retryAfterMs)
    }

    @Test
    fun `checkRateLimit returns denied response when sliding window log limiter denies`() = runBlocking {
        val config = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_LOG,
            limit = 2,
            windowMs = 10_000
        )
        every { configStore.get("client-1", "/api/data") } returns config
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns listOf(0L, 0L, 5000L)

        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "client-1"
            resource = "/api/data"
        })

        assertFalse(response.allowed)
        assertEquals(0L, response.remaining)
        assertEquals(5000L, response.retryAfterMs)
    }

    @Test
    fun `checkRateLimit routes to sliding window counter limiter`() = runBlocking {
        val config = RateLimitConfig(
            clientId = "client-1",
            resource = "/api/data",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
            limit = 5,
            windowMs = 30_000
        )
        every { configStore.get("client-1", "/api/data") } returns config
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns listOf(1L, 4L, 0L)

        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "client-1"
            resource = "/api/data"
        })

        assertTrue(response.allowed)
        assertEquals(4L, response.remaining)
    }

    // --- configureLimit tests ---

    @Test
    fun `configureLimit with blank clientId throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = ""
                    resource = "/api/test"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 10
                    windowMs = 60_000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("client_id"))
    }

    @Test
    fun `configureLimit with blank resource throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = ""
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 10
                    windowMs = 60_000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("resource"))
    }

    @Test
    fun `configureLimit with zero limit throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = "/api/test"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 0
                    windowMs = 60_000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("limit"))
    }

    @Test
    fun `configureLimit with negative limit throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = "/api/test"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = -5
                    windowMs = 60_000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("limit"))
    }

    @Test
    fun `configureLimit with zero windowMs throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = "/api/test"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 10
                    windowMs = 0
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("window_ms"))
    }

    @Test
    fun `configureLimit with negative windowMs throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = "/api/test"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 10
                    windowMs = -1000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
        assertTrue(ex.status.description!!.contains("window_ms"))
    }

    @Test
    fun `configureLimit with TOKEN_BUCKET saves correct config`() = runBlocking {
        val configSlot = slot<RateLimitConfig>()
        every { configStore.save(capture(configSlot)) } just Runs

        val response = stub.configureLimit(configureLimitRequest {
            clientId = "client-1"
            resource = "/api/data"
            algorithm = Algorithm.TOKEN_BUCKET
            limit = 100
            windowMs = 60_000
        })

        assertTrue(response.success)
        assertTrue(response.message.contains("TOKEN_BUCKET"))
        assertEquals("client-1", configSlot.captured.clientId)
        assertEquals("/api/data", configSlot.captured.resource)
        assertEquals(RateLimitAlgorithm.TOKEN_BUCKET, configSlot.captured.algorithm)
        assertEquals(100L, configSlot.captured.limit)
        assertEquals(60_000L, configSlot.captured.windowMs)
    }

    @Test
    fun `configureLimit with SLIDING_WINDOW_LOG saves correct config`() = runBlocking {
        val configSlot = slot<RateLimitConfig>()
        every { configStore.save(capture(configSlot)) } just Runs

        val response = stub.configureLimit(configureLimitRequest {
            clientId = "client-2"
            resource = "/api/logs"
            algorithm = Algorithm.SLIDING_WINDOW_LOG
            limit = 50
            windowMs = 30_000
        })

        assertTrue(response.success)
        assertTrue(response.message.contains("SLIDING_WINDOW_LOG"))
        assertEquals(RateLimitAlgorithm.SLIDING_WINDOW_LOG, configSlot.captured.algorithm)
    }

    @Test
    fun `configureLimit with SLIDING_WINDOW_COUNTER saves correct config`() = runBlocking {
        val configSlot = slot<RateLimitConfig>()
        every { configStore.save(capture(configSlot)) } just Runs

        val response = stub.configureLimit(configureLimitRequest {
            clientId = "client-3"
            resource = "/api/counter"
            algorithm = Algorithm.SLIDING_WINDOW_COUNTER
            limit = 200
            windowMs = 120_000
        })

        assertTrue(response.success)
        assertTrue(response.message.contains("SLIDING_WINDOW_COUNTER"))
        assertEquals(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, configSlot.captured.algorithm)
    }

    @Test
    fun `configureLimit response message includes limit and window`() = runBlocking {
        every { configStore.save(any()) } just Runs

        val response = stub.configureLimit(configureLimitRequest {
            clientId = "client-1"
            resource = "/api/data"
            algorithm = Algorithm.TOKEN_BUCKET
            limit = 42
            windowMs = 7777
        })

        assertTrue(response.success)
        assertTrue(response.message.contains("42"))
        assertTrue(response.message.contains("7777"))
    }

    @Test
    fun `configureLimit with whitespace clientId throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "   "
                    resource = "/api/test"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 10
                    windowMs = 60_000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }

    @Test
    fun `configureLimit with whitespace resource throws INVALID_ARGUMENT`() {
        val ex = assertThrows(StatusException::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = "   "
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = 10
                    windowMs = 60_000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }
}
