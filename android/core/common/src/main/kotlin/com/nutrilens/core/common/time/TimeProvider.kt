package com.nutrilens.core.common.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current time and zone.
 *
 * Injected rather than called statically so tests can pin "now". Every
 * chrononutrition figure depends on the current instant, and a suite that
 * cannot control it can only assert vague things.
 */
interface TimeProvider {
    fun now(): Instant

    /** The device's current zone. Re-read each time: users travel. */
    fun currentZone(): ZoneId
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun currentZone(): ZoneId = ZoneId.systemDefault()
}

/** A [TimeProvider] over a fixed or adjustable [Clock], for tests. */
class FixedTimeProvider(private var clock: Clock) : TimeProvider {
    override fun now(): Instant = clock.instant()
    override fun currentZone(): ZoneId = clock.zone

    fun set(clock: Clock) {
        this.clock = clock
    }
}
