package coredevices.coreapp.automation.command

import kotlin.time.Clock

/**
 * Per-key token-bucket rate limiter (PLAN §5.5: "per-command-type token bucket (e.g. 30/min) to
 * blunt runaway Tasker loops"). Pure logic over an injected clock so it is unit-testable; thread-safe
 * via a single lock since Binder calls arrive on a pool.
 *
 * Keyed by "$clientToken:$type" so one misbehaving profile can't starve another client, and bursts of
 * one command type don't exhaust the budget of another.
 */
class RateLimiter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val refillPerMinute: Int = DEFAULT_REFILL_PER_MINUTE,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val lock = Any()
    private val buckets = HashMap<String, Bucket>()
    private val refillPerMs: Double = refillPerMinute.toDouble() / 60_000.0

    /** @return true if a token was available and consumed; false ⇒ caller should be RATE_LIMITED. */
    fun tryAcquire(key: String): Boolean = synchronized(lock) {
        val now = nowMs()
        val bucket = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now) }
        val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0)
        bucket.tokens = (bucket.tokens + elapsed * refillPerMs).coerceAtMost(capacity.toDouble())
        bucket.lastRefillMs = now
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            true
        } else {
            false
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 30
        const val DEFAULT_REFILL_PER_MINUTE = 30
    }
}
