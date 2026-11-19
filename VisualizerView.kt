package com.exemplo.lockvisualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lê o áudio que está saindo do celular (Spotify, YouTube Music, qualquer app)
 * pelo Visualizer do Android e desenha as barras no ritmo.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var settings = Prefs.load(context)
    private var visualizer: Visualizer? = null
    private var running = false

    @Volatile private var target = FloatArray(0)   // vem da thread do Visualizer
    @Volatile private var lastSound = 0L
    private var levels = FloatArray(0)
    private var peaks = FloatArray(0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = FloatArray(3)
    private val density = resources.displayMetrics.density

    /** Espaço reservado no topo (para o relógio); as barras são desenhadas abaixo dele. */
    var contentTop = 0f
        set(v) { field = v; invalidate() }
    private val areaH: Float get() = max(1f, height - contentTop)

    /** true se o celular conseguiu ligar o captador de áudio. */
    val isCapturing: Boolean get() = visualizer != null

    fun reload() {
        settings = Prefs.load(context)
        invalidate()
    }

    fun start() {
        running = true
        if (visualizer == null &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val v = Visualizer(0) // sessão 0 = mistura de todo o áudio do celular
                v.setEnabled(false)
                v.setCaptureSize(Visualizer.getCaptureSizeRange()[1])
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, wave: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft != null) onFft(fft)
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
                v.setEnabled(true)
                visualizer = v
            } catch (e: Exception) {
                visualizer = null
            }
        }
        postInvalidateOnAnimation()
    }

    fun stop() {
        running = false
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---------- Áudio -> níveis das barras ----------

    private fun onFft(fft: ByteArray) {
        val n = settings.bars
        val bins = fft.size / 2
        val minBin = 2
        val maxBin = max(minBin + n + 1, bins / 2) // até ~11 kHz, onde está a música
        val ratio = (maxBin.toDouble() / minBin)
        val out = FloatArray(n)
        var loudest = 0f

        // Faixas logarítmicas (graves à esquerda, agudos à direita), cada barra com pelo menos 1 faixa
        fun edge(i: Int): Int = max(minBin + i, (minBin * ratio.pow(i.toDouble() / n)).toInt())

        for (i in 0 until n) {
            val lo = edge(i)
            val hi = min(max(edge(i + 1), lo + 1), bins)
            var m = 0f
            for (k in lo until hi) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                m = max(m, hypot(re, im))
            }
            loudest = max(loudest, m)
            val db = (20f * log10(m + 1f)) / 45f      // ~0..1
            out[i] = ((db - 0.12f) / 0.88f * settings.sensitivity).coerceIn(0f, 1f)
        }
        target = out
        if (loudest > 2f) lastSound = SystemClock.uptimeMillis()
    }

    // ---------- Desenho ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = settings.bars
        if (levels.size != n) {
            levels = FloatArray(n)
            peaks = FloatArray(n)
        }
        val now = SystemClock.uptimeMillis()
        val src = target
        val idle = now - lastSound > 1200 || src.size != n

        for (i in 0 until n) {
            val t = if (idle) {
                // sem som: uma ondinha calma, para a tela não ficar vazia
                0.05f + 0.05f * (1f + sin(now / 450.0 + i * 0.45).toFloat())
            } else src[i]
            levels[i] = if (t > levels[i]) levels[i] + (t - levels[i]) * 0.6f
            else max(t, levels[i] - 0.03f)
            peaks[i] = if (levels[i] >= peaks[i]) levels[i] else max(0f, peaks[i] - 0.007f)
        }

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        canvas.save()
        canvas.translate(0f, contentTop)
        when (settings.style) {
            0 -> drawLed(canvas, n)
            1 -> drawBars(canvas, n)
            2 -> drawMirror(canvas, n)
            else -> drawCircle(canvas, n, now)
        }
        canvas.restore()
        if (running) postInvalidateOnAnimation()
    }

    private fun colorAt(i: Int, n: Int, level: Float): Int {
        val f = if (n <= 1) 0f else i.toFloat() / (n - 1)
        hsv[1] = 1f; hsv[2] = 1f
        when (settings.palette) {
            0 -> hsv[0] = f * 290f                                  // vermelho -> roxo
            1 -> { hsv[0] = settings.hue; hsv[1] = 0.85f }          // cor escolhida
            2 -> hsv[0] = level.coerceIn(0f, 1f) * 55f              // vermelho embaixo, amarelo em cima
            3 -> { hsv[0] = 170f + f * 90f; hsv[1] = 0.9f }         // ciano -> azul
            else -> hsv[0] = (300f - f * 120f + 360f) % 360f       // rosa -> ciano
        }
        return Color.HSVToColor(hsv)
    }

    /** Estilo do GIF: colunas de quadradinhos + reflexo embaixo. */
    private fun drawLed(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val rows = 22
        val base = h * 0.70f
        val cellH = (base - h * 0.05f) / rows
        val colW = w / n
        val gapX = colW * 0.2f
        val gapY = cellH * 0.25f
        for (i in 0 until n) {
            val x0 = i * colW + gapX / 2
            val x1 = (i + 1) * colW - gapX / 2
            val lit = (levels[i] * rows).roundToInt()
            val peakRow = min((peaks[i] * rows).toInt(), rows - 1)
            for (r in 0 until rows) {
                val y1 = base - r * cellH
                val y0 = y1 - cellH + gapY
                paint.color = colorAt(i, n, r.toFloat() / rows)
                paint.alpha = when {
                    r < lit -> 255
                    r == peakRow && peakRow > 0 -> 170
                    else -> 20
                }
                c.drawRect(x0, y0, x1, y1, paint)
            }
            // reflexo
            val refl = min(lit, 9)
            for (r in 0 until refl) {
                val y0 = base + gapY + r * cellH * 0.85f
                paint.color = colorAt(i, n, r.toFloat() / rows)
                paint.alpha = (80 * (1f - r / 9f)).toInt()
                c.drawRect(x0, y0, x1, y0 + cellH * 0.85f - gapY, paint)
            }
        }
    }

    private fun drawBars(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val base = h * 0.72f
        val maxH = base - h * 0.05f
        val colW = w / n
        val gap = colW * 0.25f
        val radius = (colW - gap) / 2
        for (i in 0 until n) {
            val x0 = i * colW + gap / 2
            val x1 = (i + 1) * colW - gap / 2
            val top = base - max(levels[i] * maxH, 3 * density)
            paint.color = colorAt(i, n, levels[i])
            c.drawRoundRect(x0, top, x1, base, radius, radius, paint)
            // pico
            val py = base - peaks[i] * maxH - 6 * density
            paint.alpha = 200
            c.drawRoundRect(x0, py, x1, py + 3 * density, radius, radius, paint)
            // reflexo
            paint.alpha = 55
            c.drawRoundRect(x0, base + 4 * density, x1, base + 4 * density + (base - top) * 0.3f, radius, radius, paint)
        }
    }

    private fun drawMirror(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val mid = h / 2
        val colW = w / n
        val gap = colW * 0.3f
        val radius = (colW - gap) / 2
        for (i in 0 until n) {
            val x0 = i * colW + gap / 2
            val x1 = (i + 1) * colW - gap / 2
            val half = max(levels[i] * h * 0.42f, 2 * density)
            paint.color = colorAt(i, n, levels[i])
            c.drawRoundRect(x0, mid - half, x1, mid + half, radius, radius, paint)
        }
    }

    private fun drawCircle(c: Canvas, n: Int, now: Long) {
        val w = width.toFloat(); val h = areaH
        val cx = w / 2; val cy = h / 2
        val m = min(w, h)
        val r0 = m * 0.2f
        val maxLen = m * 0.26f
        val rot = (now % 40000L) / 40000.0 * 2 * PI
        var avg = 0f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(2 * density, (2 * PI * r0 / n * 0.55).toFloat())
        for (i in 0 until n) {
            avg += levels[i]
            val a = i * 2 * PI / n + rot
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val len = 4 * density + levels[i] * maxLen
            paint.color = colorAt(i, n, levels[i])
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len), paint)
        }
        avg /= n
        paint.style = Paint.Style.FILL
        paint.color = colorAt(0, n, avg)
        paint.alpha = 45
        c.drawCircle(cx, cy, r0 * (0.8f + avg * 0.2f), paint)
    }
}
