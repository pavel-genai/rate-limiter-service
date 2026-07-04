package com.ratelimiter

import com.ratelimiter.config.ConfigStore
import com.ratelimiter.grpc.RateLimiterGrpcService
import com.ratelimiter.redis.RedisClientProvider
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("com.ratelimiter.Main")
    val port = (System.getenv("GRPC_PORT") ?: "50051").toInt()

    val redisProvider = RedisClientProvider()
    val redis = redisProvider.sync()
    val configStore = ConfigStore(redis)
    val service = RateLimiterGrpcService(configStore, redis)

    val server = ServerBuilder.forPort(port)
        .addService(service)
        .build()

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down gRPC server...")
        server.shutdown()
        redisProvider.close()
    })

    server.start()
    logger.info("Rate limiter gRPC server started on port {}", port)
    server.awaitTermination()
}
