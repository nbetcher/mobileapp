package coredevices.coreapp.automation.command

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RateLimiterTest {
    @Test fun refillUsesElapsedTimeAndNeverCreditsBackwardClockMovement() {
        var time = 0L
        val limiter = RateLimiter(capacity = 1, refillPerMinute = 60, nowMs = { time })
        assertTrue(limiter.tryAcquire("a"))
        time = 500; assertFalse(limiter.tryAcquire("a"))
        time = 0; assertFalse(limiter.tryAcquire("a"))
        time = 500; assertFalse(limiter.tryAcquire("a"))
        time = 1000; assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
    }

    @Test fun simultaneousCallersCannotOverspendAndCleanupIsScoped() {
        val limiter = RateLimiter(capacity = 5, refillPerMinute = 0)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val workers = List(20) { Thread { start.await(); if (limiter.tryAcquire("package.a:ping")) successes.incrementAndGet() } }
        workers.forEach { it.start() }; start.countDown(); workers.forEach { it.join() }
        assertEquals(5, successes.get())
        repeat(5) { assertTrue(limiter.tryAcquire("package.ab:ping")) }
        limiter.removePrefix("package.a:")
        assertTrue(limiter.tryAcquire("package.a:ping"))
        assertFalse(limiter.tryAcquire("package.ab:ping"))
    }

    @Test fun invalidConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> { RateLimiter(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { RateLimiter(refillPerMinute = -1) }
    }
}
