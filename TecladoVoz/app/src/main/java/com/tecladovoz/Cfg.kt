package com.tecladovoz

import android.content.Context

/** Ajustes guardados (los comparten la pantalla de ajustes y el teclado). */
object Cfg {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun model(c: Context): String = sp(c).getString("model", "base") ?: "base"
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v).apply()

    fun keyHeight(c: Context): Int = sp(c).getInt("kh", 48).coerceIn(36, 72)
    fun setKeyHeight(c: Context, v: Int) = sp(c).edit().putInt("kh", v).apply()

    fun threads(c: Context): Int = sp(c).getInt("th", 4).coerceIn(1, 8)
    fun setThreads(c: Context, v: Int) = sp(c).edit().putInt("th", v).apply()

    fun lang(c: Context): String = sp(c).getString("lang", "es") ?: "es"
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()
}
