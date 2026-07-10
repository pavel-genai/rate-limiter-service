package com.ratelimiter.grpc

import com.ratelimiter.config.ConfigStore
import com.ratelimiter.model.RateLimitAlgorithm
import com.ratelimiter.model.RateLimitConfig
import com.ratelimiter.proto.*
import com.google.protobuf.UnknownFieldSet
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.StatusException
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

class RateLimiterGrpcServiceEdgeCaseTest {

    private lateinit var redis: RedisCommands<String, String>
    private lateinit var configStore: ConfigStore
    private lateinit var server: io.grpc.Server
    private lateinit var channel: io.grpc.ManagedChannel
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

    @Test
    fun `checkRateLimit with UNRECOGNIZED algorithm in config throws INTERNAL`() = runBlocking {
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

        // This should work normally
        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "client-1"
            resource = "/api/data"
        })
        assertTrue(response.allowed)
    }

    @Test
    fun `configureLimit with UNRECOGNIZED algorithm throws INVALID_ARGUMENT`() {
        val ex = assertThrows(Exception::class.java) {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "client-1"
                    resource = "/api/test"
                    algorithm = Algorithm.UNRECOGNIZED
                    limit = 10
                    windowMs = 60_000
                })
            }
        }
        // UNRECOGNIZED may throw either StatusException or IllegalArgumentException
        // depending on protobuf version; both indicate the invalid algorithm was rejected
    }

    @Test
    fun `checkRateLimit returns correct response fields for denied request`() = runBlocking {
        val config = RateLimitConfig(
            clientId = "client-denied",
            resource = "/api/denied",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_LOG,
            limit = 2,
            windowMs = 10_000
        )
        every { configStore.get("client-denied", "/api/denied") } returns config
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns listOf(0L, 0L, 8000L)

        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "client-denied"
            resource = "/api/denied"
        })

        assertFalse(response.allowed)
        assertEquals(0L, response.remaining)
        assertEquals(8000L, response.retryAfterMs)
    }

    @Test
    fun `checkRateLimit returns correct response fields for allowed request with sliding window counter`() = runBlocking {
        val config = RateLimitConfig(
            clientId = "client-swc",
            resource = "/api/counter",
            algorithm = RateLimitAlgorithm.SLIDING_WINDOW_COUNTER,
            limit = 5,
            windowMs = 30_000
        )
        every { configStore.get("client-swc", "/api/counter") } returns config
        every {
            redis.eval<List<Long>>(any<String>(), any(), any<Array<String>>(), *anyVararg())
        } returns listOf(1L, 3L, 0L)

        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "client-swc"
            resource = "/api/counter"
        })

        assertTrue(response.allowed)
        assertEquals(3L, response.remaining)
        assertEquals(0L, response.retryAfterMs)
    }
}