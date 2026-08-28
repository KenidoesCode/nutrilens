package com.nutrilens.core.model.sync

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with full jitter.
 *
 * Jitter matters more than it looks: without it, every device that failed
 * during the same outage retries at the same instant and re-creates the
 * outage the moment the server recovers. Full jitter spreads them uniformly
 * across the window.
 *
 * The delay is capped so a long-offline device still retries promptly once it
 * reconnects, rather than sitting out an hours-long backoff.
 */
class RetryPolicy(
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val random: Random = Random.Default,
) {

    /** Whether another attempt is permitted after [attempts] failures. */
    fun shouldRetry(attempts: Int): Boolean = attempts < maxAttempts

    /**
     * Delay before attempt number [attempts] + 1.
     *
     * `random(0, base * 2^attempts)`, capped. Attempt 0 is immediate.
     */
    fun delayMillisFor(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val exponential = baseDelayMillis * 2.0.pow(attempts - 1)
        val ceiling = min(exponential, maxDelayMillis.toDouble()).toLong()
        return if (ceiling <= 0L) 0L else random.nextLong(ceiling + 1)
    }

    /** Absolute time the next attempt becomes eligible. */
    fun nextAttemptAt(nowEpochMillis: Long, attempts: Int): Long =
        nowEpochMillis + delayMillisFor(attempts)

    companion object {
        const val DEFAULT_BASE_DELAY_MILLIS = 2_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 5 * 60 * 1000L

        /**
         * After this many failures a record stops being retried automatically.
         *
         * It is never discarded: it stays FAILED and visible, and the user can
         * ask for another attempt. Losing a meal to a retry budget would defeat
         * the entire point of storing it locally first.
         */
        const val DEFAULT_MAX_ATTEMPTS = 8
    }
}
