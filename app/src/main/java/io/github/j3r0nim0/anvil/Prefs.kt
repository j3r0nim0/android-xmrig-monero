package io.github.j3r0nim0.anvil

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val NAME = "settings"

    const val WALLET = "wallet"
    const val THREADS = "threads"
    const val THERMAL = "thermal"
    const val CHARGING_ONLY = "charging_only"
    const val WIFI_ONLY = "wifi_only"
    const val QUIET_ON = "quiet_on"
    const val QUIET_START = "quiet_start" // minutes from midnight
    const val QUIET_END = "quiet_end"
    const val DEVICE_HARM = "device_harm"
    const val CPU100 = "cpu100_count"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
