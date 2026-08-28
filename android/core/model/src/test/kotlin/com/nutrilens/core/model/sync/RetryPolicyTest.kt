package com.nutrilens.core.model.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Backoff behaviour.
 *
 * A deterministic [Random] pins the jitter so the growth curve and the cap can
 * be asserted exactly rather than approximately.
 */
class RetryPolicyTest {

    /** Always returns the top of the jitter range, exposing the true ceiling. */
    private class MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = until - 1
    }

    /** Always returns the bottom of the range. */
    private class MinRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = 0L
    }

    private fun policy(random: Random) = RetryPolicy(
        baseDelayMillis = 2_000L,
        maxDelayMillis = 60_000L,
        maxAttempts = 5,
        random = random,
    )

    @Test
    fun `the first attempt is immediate`() {
        assertEquals(0L, policy(MaxRandom()).delayMillisFor(0))
    }

    @Test
    fun `the delay ceiling doubles with each failure`() {
        val subject = policy(MaxRandom())

        // Full jitter draws from [0, base * 2^(n-1)]; MaxRandom takes the top.
        assertEquals(2_000L, subject.delayMillisFor(1))
        assertEquals(4_000L, subject.delayMillisFor(2))
        assertEquals(8_000L, subject.delayMillisFor(3))
        assertEquals(16_000L, subject.delayMillisFor(4))
    }

    @Test
    fun `the delay is capped so a reconnecting device retries promptly`() {
        val subject = policy(MaxRandom())

        // Without a cap, attempt 20 would be years away and the device would
        // never retry after coming back online.
        assertEquals(60_000L, subject.delayMillisFor(20))
    }

    @Test
    fun `jitter can produce an immediate retry`() {
        // The point of full jitter: not every device waits the same time.
        assertEquals(0L, policy(MinRandom()).delayMillisFor(4))
    }

    @Test
    fun `jitter spreads retries across the window`() {
        val subject = RetryPolicy(random = Random(seed = 1234))
        val delays = (1..200).map { subject.delayMillisFor(4) }

        assertTrue("jitter should produce varied delays", delays.distinct().size > 50)
        assertTrue(delays.all { it >= 0 })
    }

    @Test
    fun `retries stop once the budget is spent`() {
        val subject = policy(MaxRandom())

        assertTrue(subject.shouldRetry(0))
        assertTrue(subject.shouldRetry(4))
        assertFalse(subject.shouldRetry(5))
        assertFalse(subject.shouldRetry(50))
    }

    @Test
    fun `the next attempt time is now plus the delay`() {
        val subject = policy(MaxRandom())
        val now = 1_700_000_000_000L

        assertEquals(now + 4_000L, subject.nextAttemptAt(now, attempts = 2))
    }

    @Test
    fun `the defaults are sane for a mobile client`() {
        val subject = RetryPolicy(random = MaxRandom())

        // Long enough to survive a server restart, short enough that a user who
        // reopens the app sees their meal upload rather than sit in a queue.
        assertEquals(RetryPolicy.DEFAULT_MAX_DELAY_MILLIS, subject.delayMillisFor(30))
        assertTrue(subject.shouldRetry(RetryPolicy.DEFAULT_MAX_ATTEMPTS - 1))
        assertFalse(subject.shouldRetry(RetryPolicy.DEFAULT_MAX_ATTEMPTS))
    }
}
