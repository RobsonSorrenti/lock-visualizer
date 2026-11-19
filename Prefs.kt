package com.exemplo.lockvisualizer

import android.content.Context
import android.content.SharedPreferences

/** Todas as configurações que o usuário pode mudar. */
data class VisSettings(
    val enabled: Boolean,
    val style: Int,        // 0 LED, 1 Barras, 2 Espelhado, 3 Circular
    val palette: Int,      // 0 Arco-íris, 1 Cor única, 2 Fogo, 3 Oceano, 4 Neon
    val hue: Float,        // 0..360, usado na "Cor única"
    val bars: Int,         // quantidade de barras
    val sensitivity: Float,
    val showClock: Boolean,
    val keepScreenOn: Boolean,
    val rotate: Boolean,
    val showTrack: Boolean, // nome da música e artista   // gira para horizontal junto com o celular
)

object Prefs {
    const val K_ENABLED = "enabled"
    const val K_STYLE = "style"
    const val K_PALETTE = "palette"
    const val K_HUE = "hue"
    const val K_BARS = "bars"
    const val K_SENS = "sensitivity"
    const val K_CLOCK = "clock"
    const val K_SCREEN_ON = "screen_on"
    const val K_ROTATE = "rotate"
    const val K_TRACK = "track"

    val STYLES = listOf("LED (blocos)", "Barras", "Espelhado", "Circular")
    val PALETTES = listOf("Arco-íris", "Cor única", "Fogo", "Oceano", "Neon")

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("visualizer", Context.MODE_PRIVATE)

    fun load(c: Context): VisSettings {
        val p = sp(c)
        return VisSettings(
            enabled = p.getBoolean(K_ENABLED, false),
            style = p.getInt(K_STYLE, 0),
            palette = p.getInt(K_PALETTE, 0),
            hue = p.getInt(K_HUE, 190).toFloat(),
            bars = p.getInt(K_BARS, 32),
            sensitivity = p.getInt(K_SENS, 100) / 100f,
            showClock = p.getBoolean(K_CLOCK, true),
            keepScreenOn = p.getBoolean(K_SCREEN_ON, false),
            rotate = p.getBoolean(K_ROTATE, true),
            showTrack = p.getBoolean(K_TRACK, true),
        )
    }

    fun putInt(c: Context, key: String, value: Int) = sp(c).edit().putInt(key, value).apply()
    fun putBool(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()
}
