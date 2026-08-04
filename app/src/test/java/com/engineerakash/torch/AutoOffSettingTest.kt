package com.engineerakash.torch

import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutoOffSettingTest {

    @Before
    fun pinLocale() {
        // formatCountdown renders digits in the default locale; pin it so the
        // expected literals below hold on any machine
        Locale.setDefault(Locale.US)
    }

    @Test
    fun formatCountdown_underAnHour() {
        assertEquals("4:32", formatCountdown(272))
    }

    @Test
    fun formatCountdown_padsSeconds() {
        assertEquals("5:00", formatCountdown(300))
        assertEquals("0:09", formatCountdown(9))
    }

    @Test
    fun formatCountdown_hourBoundary() {
        assertEquals("59:59", formatCountdown(3599))
        assertEquals("1:00:00", formatCountdown(3600))
    }

    @Test
    fun formatCountdown_padsMinutesAndSecondsOverAnHour() {
        assertEquals("1:04:05", formatCountdown(3845))
    }

    // 2026-08-04 10:00:00.000 in the default timezone, matching what
    // nextOccurrenceEpochMs uses internally
    private val now: Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.AUGUST, 4, 10, 0, 0)
    }

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun nextOccurrence_futureTimeResolvesToToday() {
        assertEquals(at(18, 30), nextOccurrenceEpochMs(18, 30, now.timeInMillis))
    }

    @Test
    fun nextOccurrence_pastTimeRollsToTomorrow() {
        assertEquals(at(9, 0, dayOffset = 1), nextOccurrenceEpochMs(9, 0, now.timeInMillis))
    }

    @Test
    fun nextOccurrence_exactlyNowRollsToTomorrow() {
        assertEquals(at(10, 0, dayOffset = 1), nextOccurrenceEpochMs(10, 0, now.timeInMillis))
    }
}
