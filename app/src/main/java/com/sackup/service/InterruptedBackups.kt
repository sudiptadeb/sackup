package com.sackup.service

import android.content.Context

/**
 * Remembers which backup groups still need to run after a drive was unplugged mid-backup,
 * so the app can offer "Continue?" when the drive comes back. Stored in the "sackup" prefs
 * (no schema change). Ids are comma-separated.
 */
object InterruptedBackups {
    private const val PREFS = "sackup"
    private const val KEY = "interrupted_group_ids"

    fun load(context: Context): List<Long> =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, groupIds: List<Long>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (groupIds.isEmpty()) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, encode(groupIds)).apply()
    }

    fun clear(context: Context) = save(context, emptyList())

    /** Drop [finishedGroupIds] from the remembered list (e.g. after a successful re-run). */
    fun remove(context: Context, finishedGroupIds: Collection<Long>) {
        val remaining = load(context).filterNot { it in finishedGroupIds }
        save(context, remaining)
    }

    internal fun encode(ids: List<Long>): String = ids.distinct().joinToString(",")

    internal fun parse(raw: String?): List<Long> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()
}
