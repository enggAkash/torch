package com.engineerakash.torch

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's single SharedPreferences file. Every persisted setting goes through here
 * so key names and storage shapes live in one place.
 */
object TorchPrefs {

    private const val PREFS_NAME = "torch_prefs"

    private const val KEY_STROBE_RATE = "strobe_rate"

    private const val KEY_AUTO_OFF_MODE = "auto_off_mode"
    private const val KEY_AUTO_OFF_MINUTES = "auto_off_minutes"
    private const val KEY_AUTO_OFF_HOUR = "auto_off_hour"
    private const val KEY_AUTO_OFF_MINUTE = "auto_off_minute"
    private const val AUTO_OFF_MODE_NEVER = 0
    private const val AUTO_OFF_MODE_AFTER_MINUTES = 1
    private const val AUTO_OFF_MODE_AT_TIME = 2

    // The framework caches the instance per file name, so this stays cheap to call
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadStrobeRate(context: Context, default: Int): Int =
        prefs(context).getInt(KEY_STROBE_RATE, default)

    fun saveStrobeRate(context: Context, rate: Int) {
        prefs(context).edit().putInt(KEY_STROBE_RATE, rate).apply()
    }

    fun loadAutoOffSetting(context: Context): AutoOffSetting {
        val prefs = prefs(context)
        return when (prefs.getInt(KEY_AUTO_OFF_MODE, AUTO_OFF_MODE_NEVER)) {
            AUTO_OFF_MODE_AFTER_MINUTES ->
                AutoOffSetting.AfterMinutes(prefs.getInt(KEY_AUTO_OFF_MINUTES, 5))
            AUTO_OFF_MODE_AT_TIME ->
                AutoOffSetting.AtTime(
                    prefs.getInt(KEY_AUTO_OFF_HOUR, 0),
                    prefs.getInt(KEY_AUTO_OFF_MINUTE, 0),
                )
            else -> AutoOffSetting.Never
        }
    }

    fun saveAutoOffSetting(context: Context, setting: AutoOffSetting) {
        val editor = prefs(context).edit()
        when (setting) {
            AutoOffSetting.Never -> editor.putInt(KEY_AUTO_OFF_MODE, AUTO_OFF_MODE_NEVER)
            is AutoOffSetting.AfterMinutes -> editor
                .putInt(KEY_AUTO_OFF_MODE, AUTO_OFF_MODE_AFTER_MINUTES)
                .putInt(KEY_AUTO_OFF_MINUTES, setting.minutes)
            is AutoOffSetting.AtTime -> editor
                .putInt(KEY_AUTO_OFF_MODE, AUTO_OFF_MODE_AT_TIME)
                .putInt(KEY_AUTO_OFF_HOUR, setting.hour)
                .putInt(KEY_AUTO_OFF_MINUTE, setting.minute)
        }
        editor.apply()
    }
}
