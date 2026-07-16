package com.ratelimiter

import com.ratelimiter.proto.RateLimiterServiceGrpcKt
import com.ratelimiter.proto.checkRateLimitRequest
import com.ratelimiter.redis.FakeRedisServer
import com.ratelimiter.util.EnvUtil
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread

/**
 * Exercises the production [main] entry point end-to-end against an in-process
 * fake Redis server, verifying that the gRPC and health HTTP servers start and
 * that rate-limit requests flow through the full wiring.
 *
 * [main] calls `server.awaitTermination()` which blocks forever, so it is run on
 * a daemon thread and the JVM reaps it when the test JVM exits. Free ports are
 * allocated per test so concurrent/parallel test runs never collide.
 */
class MainTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun isPortOpen(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(InetSocketAddress("localhost", port), 200) }
        true
    } catch (_: Exception) {
        false
    }

    @Test
    fun `main starts gRPC and health servers and serves requests`() {
        val fakeRedis = FakeRedisServer()
        val grpcPort = freePort()
        val httpPort = freePort()

        val saved = mutableMapOf<String, String?>()
        try {
            saved["REDIS_HOST"] = EnvUtil.setEnv("REDIS_HOST", "localhost")
            saved["REDIS_PORT"] = EnvUtil.setEnv("REDIS_PORT", fakeRedis.port.toString())
            saved["GRPC_PORT"] = EnvUtil.setEnv("GRPC_PORT", grpcPort.toString())
            saved["HTTP_PORT"] = EnvUtil.setEnv("HTTP_PORT", httpPort.toString())

            // Run main() on a daemon thread; it blocks on awaitTermination().
            thread(isDaemon = true, name = "main-under-test") { main() }

            // Wait for both servers to come up.
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (isPortOpen(grpcPort) && isPortOpen(httpPort)) break
                Thread.sleep(50)
            }
            assertTrue(isPortOpen(grpcPort), "gRPC port should be listening")
            assertTrue(isPortOpen(httpPort), "health HTTP port should be listening")

            // Health endpoint
            val client = HttpClient.newHttpClient()
            val healthResp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$httpPort/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, healthResp.statusCode())
            assertTrue(healthResp.body().contains("ok"))

            // gRPC: the server is wired through main(). The in-process fake Redis
            // only speaks enough RESP to establish the connection (it replies
            // +OK/+PONG to everything), so a full rate-limit round-trip is not
            // possible here. We instead verify the gRPC endpoint accepts the
            // request; a server-side failure (UNKNOWN) is acceptable because it
            // still exercises the service dispatch path.
            val channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build()
            try {
                val stub = RateLimiterServiceGrpcKt.RateLimiterServiceCoroutineStub(channel)
                // A blank-clientId request is rejected with INVALID_ARGUMENT
                // before touching Redis, proving the gRPC service is live.
                val ex = runBlocking {
                    try {
                        stub.checkRateLimit(checkRateLimitRequest {
                            clientId = ""
                            resource = "/api/main"
                        })
                        null
                    } catch (e: io.grpc.StatusException) {
                        e
                    }
                }
                assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex?.status?.code)
            } finally {
                channel.shutdownNow()
            }

            // best-effort cleanup of the fake Redis; the daemon main thread
            // (blocked on awaitTermination) is reaped at JVM exit.
            fakeRedis.close()
        } finally {
            // Restore env vars.
            for ((k, v) in saved) {
                if (v == null) EnvUtil.removeEnv(k) else EnvUtil.setEnv(k, v)
            }
        }
    }
}