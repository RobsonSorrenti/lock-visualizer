package com.exemplo.lockvisualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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
    @Volatile private var wave = ByteArray(0)        // forma de onda (para os estilos com "onda")
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
                    override fun onWaveFormDataCapture(vis: Visualizer?, w: ByteArray?, rate: Int) {
                        if (w != null) wave = w.copyOf()
                    }
                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) {
                        if (fft != null) onFft(fft)
                    }
                }, Visualizer.getMaxCaptureRate(), true, true)
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
            3 -> drawCircle(canvas, n, now)
            4 -> drawBurst(canvas, n, now)
            5 -> drawFlames(canvas, n)
            6 -> drawClassic(canvas, n)
            7 -> drawPerspective(canvas, n, now, idle)
            else -> drawSymWave(canvas, n)
        }
        canvas.restore()
        if (running) postInvalidateOnAnimation()
    }

    private fun colorAt(i: Int, n: Int, level: Float): Int =
        colorF(if (n <= 1) 0f else i.toFloat() / (n - 1), level)

    /** f = posição (0 esquerda .. 1 direita), level = altura (0 embaixo .. 1 em cima). */
    private fun colorF(f: Float, level: Float): Int {
        val lv = level.coerceIn(0f, 1f)
        hsv[1] = 1f; hsv[2] = 1f
        when (settings.palette) {
            0 -> hsv[0] = f * 290f                                  // vermelho -> roxo
            1 -> { hsv[0] = settings.hue; hsv[1] = 0.85f }          // cor escolhida
            2 -> hsv[0] = lv * 55f                                  // vermelho embaixo, amarelo em cima
            3 -> { hsv[0] = 170f + f * 90f; hsv[1] = 0.9f }         // ciano -> azul
            4 -> hsv[0] = (300f - f * 120f + 360f) % 360f          // rosa -> ciano
            5 -> hsv[0] = when {                                    // VU: verde, amarelo, vermelho
                lv < 0.55f -> 125f
                lv < 0.8f -> 55f
                else -> 0f
            }
            else -> hsv[0] = (30f - f * 160f + 360f) % 360f        // pôr do sol: laranja -> roxo -> azul
        }
        return Color.HSVToColor(hsv)
    }

    /** Cor para degradês verticais: t = 0 em cima, 1 embaixo. */
    private fun gradColor(t: Float): Int = colorF(t, 1f - t)

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

    // ================= Estilos novos =================

    private fun waveSample(i: Int, count: Int, idle: Boolean, now: Long): Float {
        val w = wave
        if (idle || w.isEmpty()) return 0.25f * sin(now / 300.0 + i * 0.35).toFloat() * sin(i * 0.05).toFloat()
        val k = (i.toLong() * (w.size - 1) / max(1, count - 1)).toInt()
        return ((w[k].toInt() and 0xFF) - 128) / 128f
    }

    private fun avgLevel(n: Int): Float {
        var a = 0f
        for (i in 0 until n) a += levels[i]
        return a / max(1, n)
    }

    // ---- Explosão: raios saindo do centro + anel tremido, com rastro que dá zoom (estilo Milkdrop) ----
    private var bmpA: Bitmap? = null
    private var bmpB: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fx = Matrix()
    private val path = Path()

    private fun drawBurst(c: Canvas, n: Int, now: Long) {
        val bw = max(1, width / 2)
        val bh = max(1, areaH.toInt() / 2)
        if (bmpA == null || bmpA!!.width != bw || bmpA!!.height != bh) {
            bmpA = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmpB = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        }
        val prev = bmpA!!
        val cur = bmpB!!
        val bc = Canvas(cur)
        val cx = bw / 2f; val cy = bh / 2f
        val avg = avgLevel(n)

        // 1) quadro anterior, um pouco maior e girado = efeito de "túnel"
        bc.drawColor(Color.BLACK)
        fx.reset()
        fx.postScale(1.045f + avg * 0.04f, 1.045f + avg * 0.04f, cx, cy)
        fx.postRotate(0.7f, cx, cy)
        bmpPaint.alpha = 225
        bc.drawBitmap(prev, fx, bmpPaint)

        // 2) leve tinta da cor do tema por cima de tudo
        paint.style = Paint.Style.FILL
        paint.color = gradColor(0.2f)
        paint.alpha = (12 + avg * 30).toInt()
        bc.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), paint)

        // 3) raios
        val m = min(bw, bh).toFloat()
        val rot = (now % 20000L) / 20000.0 * 2 * PI
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.6f
        val rays = n * 2
        for (i in 0 until rays) {
            val idx = if (i < n) i else rays - 1 - i
            val lv = levels[idx]
            val a = i * 2 * PI / rays + rot
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val r1 = m * 0.08f
            val r2 = r1 + lv * m * 0.55f
            paint.color = colorAt(idx, n, lv)
            paint.alpha = 200
            bc.drawLine(cx + ca * r1, cy + sa * r1, cx + ca * r2, cy + sa * r2, paint)
        }

        // 4) anel "rabiscado" feito com a forma de onda
        paint.color = Color.WHITE
        for (pass in 0 until 2) {
            path.reset()
            val pts = 90
            val baseR = m * (0.28f + avg * 0.12f) + pass * 3f
            for (k in 0..pts) {
                val a = k * 2 * PI / pts + pass * 0.2
                val r = baseR + waveSample(k % pts, pts, false, now) * m * 0.12f
                val x = cx + cos(a).toFloat() * r
                val y = cy + sin(a).toFloat() * r
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.alpha = if (pass == 0) 230 else 120
            paint.strokeWidth = if (pass == 0) 1.6f else 1f
            bc.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL

        // 5) mostra ampliado na tela e troca os quadros
        fx.reset()
        fx.postScale(width.toFloat() / bw, areaH / bh)
        bmpPaint.alpha = 255
        c.drawBitmap(cur, fx, bmpPaint)
        bmpA = cur; bmpB = prev
    }

    // ---- Chamas: espectro preenchido com várias linhas finas e degradê vertical ----
    private fun drawFlames(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val base = h * 0.95f
        val top = h * 0.05f
        paint.shader = LinearGradient(0f, top, 0f, base,
            intArrayOf(colorF(1f, 1f), colorF(0.5f, 0.5f), colorF(0f, 0f)), null, Shader.TileMode.CLAMP)
        // fundo com a cor de cima bem escura, como no exemplo
        paint.alpha = 45
        c.drawRect(0f, top, w, base, paint)
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        val step = 3 * density
        paint.strokeWidth = step * 0.8f
        var x = 0f
        while (x < w) {
            val pos = x / w * (n - 1)
            val i0 = pos.toInt().coerceIn(0, n - 1)
            val i1 = min(i0 + 1, n - 1)
            val fr = pos - i0
            var lv = levels[i0] * (1 - fr) + levels[i1] * fr
            lv *= 0.8f + 0.4f * rnd.nextFloat()           // pontas irregulares, tipo fogo
            val hgt = lv.coerceIn(0f, 1f) * (base - top)
            c.drawLine(x, base, x, base - hgt, paint)
            x += step
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
    }
    private val rnd = java.util.Random()

    // ---- Clássico: poucas colunas largas, azul embaixo, amarelo no meio, vermelho em cima ----
    private fun drawClassic(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val cols = (n / 4).coerceIn(6, 16)
        val rows = 16
        val padX = w * 0.06f
        val base = h * 0.9f
        val topY = h * 0.1f
        val cellH = (base - topY) / rows
        val colW = (w - 2 * padX) / cols
        // fundo arredondado preto, como um painel
        paint.color = Color.BLACK
        c.drawRoundRect(padX * 0.4f, topY - cellH, w - padX * 0.4f, base + cellH, 24 * density, 24 * density, paint)
        for (j in 0 until cols) {
            var lv = 0f
            val from = j * n / cols
            val to = max(from + 1, (j + 1) * n / cols)
            for (i in from until min(to, n)) lv = max(lv, levels[i])
            val lit = (lv * rows).roundToInt()
            val x0 = padX + j * colW + colW * 0.12f
            val x1 = padX + (j + 1) * colW - colW * 0.12f
            for (r in 0 until rows) {
                val f = r.toFloat() / rows
                paint.color = when {
                    f < 0.5f -> 0xFF1E3DFF.toInt()
                    f < 0.75f -> 0xFFFFEA00.toInt()
                    else -> 0xFFFF1A1A.toInt()
                }
                paint.alpha = if (r < lit) 255 else 22
                val y1 = base - r * cellH
                c.drawRect(x0, y1 - cellH * 0.72f, x1, y1, paint)
            }
        }
    }

    // ---- Perspectiva: onda em cima + barras em 3D indo para o fundo ----
    private fun drawPerspective(c: Canvas, n: Int, now: Long, idle: Boolean) {
        val w = width.toFloat(); val h = areaH
        // onda
        val midY = h * 0.16f
        val amp = h * 0.13f
        val lines = (w / (3 * density)).toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        for (k in 0 until lines) {
            val x = k * 3 * density
            val v = waveSample(k, lines, idle, now) * amp
            paint.color = colorF(k.toFloat() / lines, 0.3f + Math.abs(v) / amp * 0.7f)
            c.drawLine(x, midY - v, x, midY + v, paint)
        }
        paint.style = Paint.Style.FILL

        // barras em perspectiva
        val cols = (n / 3).coerceIn(4, 12)
        val rows = 14
        val vpX = w / 2
        val vpY = h * 0.3f
        val base = h * 0.97f
        val colW = w / cols
        for (j in 0 until cols) {
            var lv = 0f
            val from = j * n / cols
            val to = max(from + 1, (j + 1) * n / cols)
            for (i in from until min(to, n)) lv = max(lv, levels[i])
            val lit = (lv * rows).roundToInt()
            val bx0 = j * colW + colW * 0.15f
            val bx1 = (j + 1) * colW - colW * 0.15f
            for (r in 0 until rows) {
                val t0 = r.toFloat() / rows
                val t1 = (r + 0.75f) / rows
                fun px(x: Float, t: Float) = vpX + (x - vpX) * (1f - t * 0.55f)
                fun py(t: Float) = base - t * (base - vpY) * 0.9f
                path.reset()
                path.moveTo(px(bx0, t0), py(t0))
                path.lineTo(px(bx1, t0), py(t0))
                path.lineTo(px(bx1, t1), py(t1))
                path.lineTo(px(bx0, t1), py(t1))
                path.close()
                paint.color = colorF(j.toFloat() / max(1, cols - 1), t0)
                paint.alpha = if (r < lit) (255 * (0.55f + 0.45f * (1 - t0))).toInt() else 14
                c.drawPath(path, paint)
            }
        }
    }

    // ---- Onda simétrica: barras finas espelhadas, graves no meio, formato de losango, degradê ----
    private fun drawSymWave(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val mid = h / 2
        val maxHalf = h * 0.42f
        val m = n * 2
        val colW = w / m
        val barW = colW * 0.5f
        paint.shader = LinearGradient(0f, mid - maxHalf, 0f, mid + maxHalf,
            intArrayOf(gradColor(0f), gradColor(0.5f), gradColor(1f)), null, Shader.TileMode.CLAMP)
        val center = (m - 1) / 2f
        for (j in 0 until m) {
            val d = Math.abs(j - center) / center               // 0 no meio, 1 nas pontas
            val idx = (d * (n - 1)).roundToInt().coerceIn(0, n - 1)
            val lv = levels[idx] * (1f - d * 0.65f)            // formato de losango
            val half = max(2 * density, lv * maxHalf)
            val x = j * colW + (colW - barW) / 2
            c.drawRoundRect(x, mid - half, x + barW, mid + half, barW / 2, barW / 2, paint)
        }
        paint.shader = null
    }
}
