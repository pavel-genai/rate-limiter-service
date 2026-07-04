package com.ratelimiter.integration

import com.ratelimiter.config.ConfigStore
import com.ratelimiter.grpc.RateLimiterGrpcService
import com.ratelimiter.proto.*
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.StatusException
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Integration test that spins up a real Redis container via Testcontainers,
 * starts the gRPC server in-process, and exercises the full flow:
 * configure a limit, then check rate limiting.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RateLimiterIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        private lateinit var redisClient: RedisClient
        private lateinit var commands: RedisCommands<String, String>
        private lateinit var server: io.grpc.Server
        private lateinit var stub: RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub
        private lateinit var channel: io.grpc.ManagedChannel
        private const val GRPC_PORT = 0 // use ephemeral port

        @BeforeAll
        @JvmStatic
        fun setup() {
            redisClient = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(6379)}")
            commands = redisClient.connect().sync()

            val configStore = ConfigStore(commands)
            val service = RateLimiterGrpcService(configStore, commands)

            server = ServerBuilder.forPort(GRPC_PORT)
                .addService(service)
                .build()
                .start()

            val port = server.port
            channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build()

            stub = RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub(channel)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            channel.shutdownNow()
            server.shutdownNow()
            redisClient.shutdown()
        }
    }

    @BeforeEach
    fun beforeEach() {
        commands.flushall()
    }

    @Test
    @Order(1)
    fun `configure and check token bucket rate limit`() = runBlocking {
        // Configure a token bucket limit
        val configResponse = stub.configureLimit(configureLimitRequest {
            clientId = "integration-client"
            resource = "/api/data"
            algorithm = Algorithm.TOKEN_BUCKET
            limit = 3
            windowMs = 10_000
        })

        assertTrue(configResponse.success)

        // First 3 requests should be allowed
        for (i in 1..3) {
            val response = stub.checkRateLimit(checkRateLimitRequest {
                clientId = "integration-client"
                resource = "/api/data"
            })
            assertTrue(response.allowed, "Request $i should be allowed")
        }

        // 4th request should be denied
        val denied = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "integration-client"
            resource = "/api/data"
        })
        assertFalse(denied.allowed)
        assertTrue(denied.retryAfterMs > 0)
    }

    @Test
    @Order(2)
    fun `configure and check sliding window log rate limit`() = runBlocking {
        val configResponse = stub.configureLimit(configureLimitRequest {
            clientId = "swl-client"
            resource = "/api/logs"
            algorithm = Algorithm.SLIDING_WINDOW_LOG
            limit = 2
            windowMs = 10_000
        })

        assertTrue(configResponse.success)

        assertTrue(stub.checkRateLimit(checkRateLimitRequest {
            clientId = "swl-client"
            resource = "/api/logs"
        }).allowed)

        assertTrue(stub.checkRateLimit(checkRateLimitRequest {
            clientId = "swl-client"
            resource = "/api/logs"
        }).allowed)

        val denied = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "swl-client"
            resource = "/api/logs"
        })
        assertFalse(denied.allowed)
    }

    @Test
    @Order(3)
    fun `configure and check sliding window counter rate limit`() = runBlocking {
        val configResponse = stub.configureLimit(configureLimitRequest {
            clientId = "swc-client"
            resource = "/api/counter"
            algorithm = Algorithm.SLIDING_WINDOW_COUNTER
            limit = 2
            windowMs = 10_000
        })

        assertTrue(configResponse.success)

        assertTrue(stub.checkRateLimit(checkRateLimitRequest {
            clientId = "swc-client"
            resource = "/api/counter"
        }).allowed)

        assertTrue(stub.checkRateLimit(checkRateLimitRequest {
            clientId = "swc-client"
            resource = "/api/counter"
        }).allowed)

        val denied = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "swc-client"
            resource = "/api/counter"
        })
        assertFalse(denied.allowed)
    }

    @Test
    @Order(4)
    fun `check rate limit without config returns NOT_FOUND`() = runBlocking {
        val exception = assertThrows<StatusException> {
            runBlocking {
                stub.checkRateLimit(checkRateLimitRequest {
                    clientId = "unknown-client"
                    resource = "/api/missing"
                })
            }
        }
        assertEquals(io.grpc.Status.NOT_FOUND.code, exception.status.code)
    }

    @Test
    @Order(5)
    fun `configure with invalid parameters returns INVALID_ARGUMENT`() = runBlocking {
        val exception = assertThrows<StatusException> {
            runBlocking {
                stub.configureLimit(configureLimitRequest {
                    clientId = "bad-client"
                    resource = "/api/bad"
                    algorithm = Algorithm.TOKEN_BUCKET
                    limit = -1
                    windowMs = 1000
                })
            }
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, exception.status.code)
    }

    @Test
    @Order(6)
    fun `remaining count is returned correctly`() = runBlocking {
        stub.configureLimit(configureLimitRequest {
            clientId = "remaining-client"
            resource = "/api/remaining"
            algorithm = Algorithm.TOKEN_BUCKET
            limit = 5
            windowMs = 10_000
        })

        val response = stub.checkRateLimit(checkRateLimitRequest {
            clientId = "remaining-client"
            resource = "/api/remaining"
        })

        assertTrue(response.allowed)
        assertEquals(4L, response.remaining)
        assertEquals(0L, response.retryAfterMs)
    }
}
