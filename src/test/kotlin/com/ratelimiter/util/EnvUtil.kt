package com.ratelimiter.util

import java.lang.reflect.Field
import java.util.Collections

/**
 * Test-only helper that mutates the JVM's process environment via reflection
 * so that production code reading `System.getenv(...)` can be exercised.
 *
 * The unmodifiable map returned by `System.getenv()` is backed by a private
 * mutable `Map` field; we reach into it through reflection. Each call returns
 * the previous value (or null) so callers can restore state in a `finally`.
 *
 * Requires `--add-opens java.base/java.util=ALL-UNNAMED` (set in build.gradle.kts).
 */
object EnvUtil {

    private val envMap: MutableMap<String, String> by lazy {
        val env = System.getenv()
        val field: Field = Collections.unmodifiableMap<Any?, Any?>(java.util.HashMap<Any?, Any?>())::class.java
            .getDeclaredField("m")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(env) as MutableMap<String, String>
    }

    /** Sets [key] to [value] in the process environment; returns the previous value (or null). */
    fun setEnv(key: String, value: String): String? = envMap.put(key, value)

    /** Removes [key] from the process environment; returns the previous value (or null). */
    fun removeEnv(key: String): String? = envMap.remove(key)
}