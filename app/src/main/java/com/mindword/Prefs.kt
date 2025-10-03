package com.mindword

import android.content.Context

object Prefs {
    private const val FILE = "lexisnap_prefs"
    private const val KEY_NAME = "player_name"
    private const val KEY_LEVEL = "player_level"

    fun loadName(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, 0).getString(KEY_NAME, null)

    fun saveName(ctx: Context, name: String) {
        ctx.getSharedPreferences(FILE, 0).edit().putString(KEY_NAME, name).apply()
    }

    fun loadLevel(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, 0).getInt(KEY_LEVEL, 1)

    fun saveLevel(ctx: Context, level: Int) {
        ctx.getSharedPreferences(FILE, 0).edit().putInt(KEY_LEVEL, level).apply()
    }
}
