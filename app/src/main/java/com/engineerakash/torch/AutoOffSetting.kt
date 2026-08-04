package com.engineerakash.torch

import java.util.Calendar
import java.util.Locale

/** How long the torch may stay on before turning itself off. Applies to TORCH mode only. */
sealed class AutoOffSetting {
    data object Never : AutoOffSetting()

    /** Fixed countdown, restarted each time the torch turns on. */
    data class AfterMinutes(val minutes: Int) : AutoOffSetting()

    /** Turn off at the next occurrence of this wall-clock time (24h fields). */
    data class AtTime(val hour: Int, val minute: Int) : AutoOffSetting()
}

/** Epoch ms of the next occurrence of [hour]:[minute]; "now or already past" rolls to tomorrow. */
fun nextOccurrenceEpochMs(
    hour: Int,
    minute: Int,
    now: Long = System.currentTimeMillis(),
): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (cal.timeInMillis <= now) {
        cal.add(Calendar.DAY_OF_YEAR, 1)
    }
    return cal.timeInMillis
}

/** "4:32", or "1:04:32" once an hour or more remains. Default locale keeps digits native. */
fun formatCountdown(totalSecs: Long): String {
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
