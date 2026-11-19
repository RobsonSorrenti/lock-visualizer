package com.exemplo.lockvisualizer

import android.content.Context
import android.content.SharedPreferences

/** Todas as configurações que o usuário pode mudar. */
data class VisSettings(
    val enabled: Boolean,
    val style: Int,        // índice em STYLES
    val palette: Int,      // índice em PALETTES
    val hue: Float,        // 0..360, usado na "Cor única"
    val bars: Int,         // quantidade de barras
    val sensitivity: Float,
    val showClock: Boolean,
    val keepScreenOn: Boolean,
    val rotate: Boolean,
    val showTrack: Boolean, // nome da música e artista
    val neonText: String,   // texto do estilo Texto neon
    val imageUri: String,   // imagem/GIF do estilo Minha imagem   // gira para horizontal junto com o celular
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
    const val K_NEON = "neon_text"
    const val K_IMAGE = "image_uri"

    val STYLES = listOf(
        "LED (blocos)", "Barras", "Espelhado", "Circular",
        "Explosão", "Chamas", "Clássico", "Perspectiva 3D", "Onda simétrica",
        "Fogos de artifício", "Cruz de luz", "Monitor cardíaco", "Vórtice", "Coração pulsando",
        "Texto neon", "Minha imagem/GIF", "ECG clássico", "Estrada neon",
    )
    val PALETTES = listOf("Arco-íris", "Cor única", "Fogo", "Oceano", "Neon", "VU (verde/amarelo/vermelho)", "Pôr do sol")

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
            neonText = p.getString(K_NEON, "NEON") ?: "NEON",
            imageUri = p.getString(K_IMAGE, "") ?: "",
        )
    }

    fun putInt(c: Context, key: String, value: Int) = sp(c).edit().putInt(key, value).apply()
    fun putString(c: Context, key: String, value: String) = sp(c).edit().putString(key, value).apply()
    fun putBool(c: Context, key: String, value: Boolean) = sp(c).edit().putBoolean(key, value).apply()
}
