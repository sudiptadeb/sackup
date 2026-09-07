package com.sackup

import android.content.Context

/** What SackUp does when the USB drive is plugged in. */
enum class OnConnectMode { ASK, AUTO, OFF }

/** Stores the on-connect setting in the "sackup" prefs (key "on_connect_mode"; default [OnConnectMode.ASK]). */
object ConnectPrefs {
    private const val PREFS = "sackup"
    private const val KEY = "on_connect_mode"

    fun get(context: Context): OnConnectMode =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun set(context: Context, mode: OnConnectMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }

    /** Unknown or missing values fall back to [OnConnectMode.ASK]. */
    internal fun parse(raw: String?): OnConnectMode =
        OnConnectMode.values().firstOrNull { it.name == raw } ?: OnConnectMode.ASK
}
