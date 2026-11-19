package com.exemplo.lockvisualizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RadialGradient
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
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
import kotlin.math.sqrt

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
    @Volatile private var wave = ByteArray(0)
    private var bass = 0f
    private var bassAvg = 0f
    private var beat = false
    private var lastBeat = 0L
    private var lastFrame = 0L
    private var frameDt = 0.016f        // forma de onda (para os estilos com "onda")
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

        // detecção de batida (graves subindo de repente)
        val nb = max(1, n / 6)
        var b = 0f
        for (i in 0 until nb) b += levels[i]
        bass = b / nb
        bassAvg = bassAvg * 0.96f + bass * 0.04f
        beat = !idle && bass > 0.3f && bass > bassAvg * 1.25f && now - lastBeat > 220
        if (beat) lastBeat = now
        frameDt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.shader = null
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
            8 -> drawSymWave(canvas, n)
            9 -> drawFireworks(canvas, now, idle)
            10 -> drawCross(canvas, n, now)
            11 -> drawEcg(canvas, now, idle)
            12 -> drawVortex(canvas, n, now, idle)
            13 -> drawHeart(canvas)
            14 -> drawNeon(canvas, n)
            15 -> drawImage(canvas, n)
            16 -> drawEcgClassic(canvas, now, idle)
            else -> drawNeonRoad(canvas, n, now)
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

    // ================= Estilos novos (parte 2) =================

    private fun withAlpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    // ---- Fogos de artifício: uma explosão de faíscas a cada batida ----
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Int, val total: Int, val color: Int)
    private val sparks = ArrayList<Spark>()
    private var lastBurst = 0L

    private fun drawFireworks(c: Canvas, now: Long, idle: Boolean) {
        val w = width.toFloat(); val h = areaH
        val due = now - lastBurst > (if (idle) 2500 else 1300)
        if (beat || due) {
            lastBurst = now
            val cx = w * (0.2f + 0.6f * rnd.nextFloat())
            val cy = h * (0.15f + 0.35f * rnd.nextFloat())
            val count = if (idle) 45 else (70 + bass * 110).toInt()
            val f0 = rnd.nextFloat()
            val power = (if (idle) 2.5f else 3f + bass * 5f) * density
            repeat(count) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val sp = power * (0.3f + 0.7f * rnd.nextFloat())
                val life = 45 + rnd.nextInt(45)
                val col = colorF((f0 + rnd.nextFloat() * 0.15f) % 1f, 0.4f + 0.6f * rnd.nextFloat())
                sparks.add(Spark(cx, cy, cos(a) * sp, sin(a) * sp, life, life, col))
            }
            if (sparks.size > 1200) sparks.subList(0, sparks.size - 1200).clear()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 1.6f * density
        val g = 0.045f * density
        val it = sparks.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.vx *= 0.985f
            p.vy = p.vy * 0.985f + g
            p.x += p.vx; p.y += p.vy
            p.life--
            if (p.life <= 0 || p.y > h) { it.remove(); continue }
            val t = p.life.toFloat() / p.total
            paint.color = p.color
            paint.alpha = (255 * t).toInt()
            c.drawLine(p.x - p.vx * 5, p.y - p.vy * 5, p.x, p.y, paint)
            if (p.life % 7 == 0) {                         // brilho piscando, tipo estrelinha
                paint.color = Color.WHITE
                paint.alpha = (200 * t).toInt()
                c.drawPoint(p.x, p.y, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    // ---- Cruz de luz: cruz iluminada sobre um fundo azul com estrelas e feixes de luz ----
    private var stars = FloatArray(0)

    private fun drawCross(c: Canvas, n: Int, now: Long) {
        val w = width.toFloat(); val h = areaH
        val glow = colorF(0.7f, 0.5f)
        paint.color = 0xFF04060D.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        // névoa colorida
        paint.shader = RadialGradient(w * 0.6f, h * 0.45f, max(w, h) * 0.65f,
            intArrayOf(withAlpha(glow, (80 + bass * 70).toInt()), withAlpha(glow, 25), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // feixes de luz vindos de cima
        val mids = levels[n / 2]
        for (k in 0 until 5) {
            val x = w * (0.12f + k * 0.19f)
            val sway = sin(now / 2500.0 + k).toFloat() * w * 0.04f
            path.reset()
            path.moveTo(x - w * 0.015f, 0f)
            path.lineTo(x + w * 0.015f, 0f)
            path.lineTo(x + sway + w * 0.07f, h)
            path.lineTo(x + sway - w * 0.07f, h)
            path.close()
            paint.color = Color.WHITE
            paint.alpha = (10 + mids * 35).toInt()
            c.drawPath(path, paint)
        }
        // estrelas piscando com os agudos
        if (stars.isEmpty()) {
            stars = FloatArray(90 * 4)
            for (i in 0 until 90) {
                stars[i * 4] = rnd.nextFloat(); stars[i * 4 + 1] = rnd.nextFloat()
                stars[i * 4 + 2] = 0.6f + rnd.nextFloat() * 1.4f; stars[i * 4 + 3] = rnd.nextFloat() * 6.28f
            }
        }
        var treble = 0f
        for (i in n * 2 / 3 until n) treble += levels[i]
        treble /= max(1, n - n * 2 / 3)
        paint.color = Color.WHITE
        for (i in 0 until 90) {
            val tw = 0.5f + 0.5f * sin(now / 400.0 + stars[i * 4 + 3]).toFloat()
            paint.alpha = (60 + tw * 120 * (0.4f + treble)).toInt()
            c.drawCircle(stars[i * 4] * w, stars[i * 4 + 1] * h, stars[i * 4 + 2] * density, paint)
        }
        // a cruz
        val m = min(w, h)
        val sc = 1f + bass * 0.1f
        val cx = w / 2; val cy = h * 0.5f
        val arm = m * 0.05f * sc
        val vh = m * 0.36f * sc
        val hw = m * 0.24f * sc
        val barY = cy - vh * 0.22f
        paint.shader = RadialGradient(cx, barY, vh * 0.95f,
            intArrayOf(withAlpha(mix(glow, Color.WHITE, 0.6f), (90 + bass * 140).toInt()), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, barY, vh * 0.95f, paint)
        paint.shader = null
        val r = arm * 0.15f
        paint.color = Color.BLACK; paint.alpha = 110                     // sombra
        val sx = arm * 0.45f; val sy = arm * 0.7f
        c.drawRoundRect(cx - arm / 2 + sx, cy - vh / 2 + sy, cx + arm / 2 + sx, cy + vh / 2 + sy, r, r, paint)
        c.drawRoundRect(cx - hw / 2 + sx, barY - arm / 2 + sy, cx + hw / 2 + sx, barY + arm / 2 + sy, r, r, paint)
        paint.color = mix(0xFFF3E6C8.toInt(), Color.WHITE, bass * 0.6f)
        c.drawRoundRect(cx - arm / 2, cy - vh / 2, cx + arm / 2, cy + vh / 2, r, r, paint)
        c.drawRoundRect(cx - hw / 2, barY - arm / 2, cx + hw / 2, barY + arm / 2, r, r, paint)
    }

    // ---- Monitor cardíaco: grade em 3D e uma linha que desenha corações nas batidas ----
    private val ecg = FloatArray(240)
    private val heartQueue = ArrayDeque<Float>()
    private var lastHeart = 0L

    private fun queueHeart(amp: Float) {
        val len = 52
        for (t in 0 until len) {
            val x = -2f + 4f * t / (len - 1)
            val ax = Math.abs(x)
            val top = sqrt(max(0f, 1f - (ax - 1f) * (ax - 1f)))
            val bottom = -2.2f * (1f - ax / 2f).pow(1.2f)
            val v = (top + bottom) / 2 + (top - bottom) / 2 * sin(t * 2.6f)
            heartQueue.addLast(v / 2.2f * amp)
        }
    }

    private fun drawEcg(c: Canvas, now: Long, idle: Boolean) {
        val w = width.toFloat(); val h = areaH
        if (heartQueue.isEmpty() && (beat || now - lastHeart > (if (idle) 2200 else 1600))) {
            lastHeart = now
            queueHeart(0.55f + bass * 0.45f)
        }
        repeat(2) {
            val v = if (heartQueue.isNotEmpty()) heartQueue.removeFirst()
            else waveSample(rnd.nextInt(64), 64, idle, now) * 0.03f + (rnd.nextFloat() - 0.5f) * 0.02f
            System.arraycopy(ecg, 0, ecg, 1, ecg.size - 1)
            ecg[0] = v
        }
        val mid = h / 2
        val x0 = w * 0.05f; val x1 = w * 0.99f
        val h0 = h * 0.42f; val h1 = h * 0.12f
        fun frac(u: Float): Float { val z = 1f + 2f * u; return (1f - 1f / z) / (1f - 1f / 3f) }
        val line = colorF(0.35f, 0.6f)
        // grade
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = withAlpha(mix(line, Color.BLACK, 0.55f), 200)
        for (k in 0..24) {
            val fr = frac(k / 24f)
            val x = x0 + (x1 - x0) * fr
            val hh = h0 + (h1 - h0) * fr
            c.drawLine(x, mid - hh, x, mid + hh, paint)
        }
        for (k in -7..7) {
            val v = k / 7f
            c.drawLine(x0, mid + v * h0, x1, mid + v * h1, paint)
        }
        // linha do coração
        path.reset()
        for (i in ecg.indices) {
            val fr = frac(i / (ecg.size - 1f))
            val x = x0 + (x1 - x0) * fr
            val hh = h0 + (h1 - h0) * fr
            val y = mid - ecg[i] * hh
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.color = withAlpha(line, 70)
        paint.strokeWidth = 5f * density
        c.drawPath(path, paint)
        paint.color = mix(line, Color.WHITE, 0.25f)
        paint.strokeWidth = 1.6f * density
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    // ---- Vórtice: anéis ondulados formando um túnel que gira e acelera com a música ----
    private var vortexPhase = 0f

    private fun drawVortex(c: Canvas, n: Int, now: Long, idle: Boolean) {
        val w = width.toFloat(); val h = areaH
        val cx = w / 2; val cy = h / 2
        val bigR = hypot(w, h) / 2 * 1.15f
        val avg = avgLevel(n)
        val main = colorF(0.7f, 0.5f)
        val accent = colorF(0.08f, 0.9f)
        vortexPhase = (vortexPhase + frameDt * (0.35f + avg * 1.6f)) % 1f
        paint.shader = RadialGradient(cx, cy, bigR * 0.28f * (1f + bass * 0.6f),
            intArrayOf(withAlpha(mix(main, Color.WHITE, 0.6f), 230), withAlpha(main, 60), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, bigR * 0.28f * (1f + bass * 0.6f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        val rings = 16
        val pts = 72
        val rot = now / 4000.0
        for (k in 0 until rings) {
            val depth = ((k + vortexPhase) % rings) / rings      // 0 = centro, 1 = borda
            val r = bigR * depth.pow(1.8f)
            if (r < 2f) continue
            val base = if (k % 5 == 2) accent else main
            for (pass in 0 until 3) {
                path.reset()
                for (j in 0..pts) {
                    val a = j * 2 * PI / pts
                    val wob = sin(a * 3 + rot * 2 + pass + k).toFloat() * 0.08f +
                        sin(a * 5 - rot * 3 + k * 0.7).toFloat() * 0.05f +
                        waveSample(j % pts, pts, idle, now) * 0.06f * (0.3f + avg)
                    val rr = r * (1f + wob)
                    val x = cx + cos(a + rot + k * 0.3).toFloat() * rr
                    val y = cy + sin(a + rot + k * 0.3).toFloat() * rr
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.color = base
                paint.alpha = (sin(depth * PI).toFloat() * 190 / (pass + 1)).toInt()
                paint.strokeWidth = (0.8f + depth * 1.6f) * density
                c.drawPath(path, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    // ---- Coração pulsando: bate junto com os graves e solta ondas ----
    private val heartShape = Path().apply {
        for (i in 0..120) {
            val t = i / 120.0 * 2 * PI
            val x = (16 * sin(t).pow(3)) / 17.0
            val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)) / 17.0
            if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
        }
        close()
    }
    private val heartRings = ArrayList<FloatArray>()   // [escala, transparência]

    private fun drawHeart(c: Canvas) {
        val w = width.toFloat(); val h = areaH
        val col = colorF(0f, 0.5f)
        val size = min(w, h) * 0.3f * (0.88f + bass * 0.35f)
        if (beat) heartRings.add(floatArrayOf(size, 200f))
        c.save()
        c.translate(w / 2, h / 2)
        // ondas
        paint.style = Paint.Style.STROKE
        val it = heartRings.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r[0] += 3f * density
            r[1] *= 0.94f
            if (r[1] < 4f) { it.remove(); continue }
            c.save()
            c.scale(r[0], r[0])
            paint.color = col
            paint.alpha = r[1].toInt()
            paint.strokeWidth = 2.5f * density / r[0]
            c.drawPath(heartShape, paint)
            c.restore()
        }
        paint.style = Paint.Style.FILL
        // coração
        c.scale(size, size)
        paint.shader = RadialGradient(-0.35f, -0.4f, 1.5f,
            intArrayOf(mix(col, Color.WHITE, 0.45f), col, mix(col, Color.BLACK, 0.55f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(heartShape, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.alpha = 70
        c.drawOval(-0.62f, -0.62f, -0.25f, -0.4f, paint)
        c.restore()
    }

    // ---- Texto neon: o texto que você escrever, brilhando no ritmo ----
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        textAlign = Paint.Align.CENTER
    }

    private fun drawNeon(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        val lines = settings.neonText.ifBlank { "NEON" }.split("|").map { it.trim() }.take(3)
        val avg = avgLevel(n)
        var size = h * 0.24f / lines.size
        textPaint.textSize = size
        val widest = lines.maxOf { textPaint.measureText(it) }
        if (widest > w * 0.86f) size *= w * 0.86f / widest
        size *= 1f + bass * 0.08f
        textPaint.textSize = size
        val glowStrength = 0.35f + avg * 1.2f
        val totalH = size * 1.15f * lines.size
        lines.forEachIndexed { i, text ->
            val y = h / 2 - totalH / 2 + size * 1.15f * (i + 0.8f)
            textPaint.shader = null
            textPaint.style = Paint.Style.STROKE
            for (g in 4 downTo 1) {                                    // brilho em volta
                textPaint.strokeWidth = size * 0.07f * g
                textPaint.color = withAlpha(colorF(0.5f, 0.5f), (26 * glowStrength * (5 - g)).toInt())
                c.drawText(text, w / 2, y, textPaint)
            }
            textPaint.strokeWidth = size * 0.06f                       // contorno
            textPaint.color = mix(colorF(0.2f, 0.8f), Color.WHITE, 0.3f)
            c.drawText(text, w / 2, y, textPaint)
            textPaint.style = Paint.Style.FILL                         // preenchimento em degradê
            textPaint.shader = LinearGradient(0f, y - size * 0.8f, 0f, y + size * 0.1f,
                intArrayOf(colorF(0f, 1f), colorF(0.5f, 0.6f), colorF(1f, 0f)), null, Shader.TileMode.CLAMP)
            c.drawText(text, w / 2, y, textPaint)
            textPaint.shader = null
        }
    }

    // ---- Minha imagem/GIF: a imagem escolhida pulsa no ritmo, com barras embaixo ----
    private var imgDrawable: Drawable? = null
    private var imgUri: String? = null

    private fun loadImage() {
        if (settings.imageUri == imgUri) return
        imgUri = settings.imageUri
        (imgDrawable as? Animatable)?.stop()
        imgDrawable = null
        if (settings.imageUri.isEmpty()) return
        try {
            val uri = Uri.parse(settings.imageUri)
            val d: Drawable? = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    BitmapFactory.decodeStream(s)?.let { BitmapDrawable(resources, it) }
                }
            }
            d?.callback = this
            (d as? Animatable)?.start()
            imgDrawable = d
        } catch (_: Exception) {
            imgDrawable = null
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === imgDrawable || super.verifyDrawable(who)

    private fun drawImage(c: Canvas, n: Int) {
        val w = width.toFloat(); val h = areaH
        loadImage()
        val d = imgDrawable
        if (d == null) {
            textPaint.shader = null
            textPaint.style = Paint.Style.FILL
            textPaint.color = 0xAAFFFFFF.toInt()
            textPaint.textSize = 16 * density
            c.drawText("Escolha uma imagem ou GIF", w / 2, h * 0.45f, textPaint)
            c.drawText("nas configurações do app", w / 2, h * 0.45f + 22 * density, textPaint)
        } else {
            val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth.toFloat() else w
            val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight.toFloat() else h
            val fit = min(w * 0.85f / iw, h * 0.68f / ih) * (1f + bass * 0.12f)
            val dw = iw * fit; val dh = ih * fit
            val cx = w / 2; val cy = h * 0.4f
            d.setBounds((cx - dw / 2).toInt(), (cy - dh / 2).toInt(), (cx + dw / 2).toInt(), (cy + dh / 2).toInt())
            d.draw(c)
        }
        // barras pequenas embaixo
        val base = h * 0.97f
        val maxH = h * 0.16f
        val colW = w / n
        val gap = colW * 0.3f
        for (i in 0 until n) {
            paint.color = colorAt(i, n, levels[i])
            val top = base - max(levels[i] * maxH, 2 * density)
            c.drawRoundRect(i * colW + gap / 2, top, (i + 1) * colW - gap / 2, base, colW / 3, colW / 3, paint)
        }
    }

    // ================= Estilos novos (parte 3) =================

    // ---- ECG clássico: traçado de monitor de hospital varrendo a tela ----
    private var sweep = FloatArray(0)
    private var sweepPos = 0
    private val beatQueue = ArrayDeque<Float>()
    private var lastPulse = 0L

    private fun gauss(t: Float, mu: Float, sd: Float) = kotlin.math.exp(-((t - mu) * (t - mu)) / (2 * sd * sd))

    private fun queuePulse(amp: Float) {
        for (i in 0 until 50) {
            val t = i.toFloat()
            val v = 0.12f * gauss(t, 6f, 3f) - 0.12f * gauss(t, 17f, 1f) + 1f * gauss(t, 20f, 1.2f) -
                0.35f * gauss(t, 23f, 1.2f) + 0.25f * gauss(t, 38f, 5f)
            beatQueue.addLast(v * amp)
        }
    }

    private fun drawEcgClassic(c: Canvas, now: Long, idle: Boolean) {
        val w = width.toFloat(); val h = areaH
        val step = 2 * density
        val cols = max(10, (w / step).toInt())
        if (sweep.size != cols) { sweep = FloatArray(cols); sweepPos = 0 }
        val line = colorF(0.35f, 0.6f)
        // fundo verde escuro com centro mais claro
        paint.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.7f,
            intArrayOf(mix(line, Color.BLACK, 0.8f), mix(line, Color.BLACK, 0.93f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // grade
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = 0x70A0522D
        val cell = 26 * density
        var gx = 0f
        while (gx < w) { c.drawLine(gx, 0f, gx, h, paint); gx += cell }
        var gy = h / 2 % cell
        while (gy < h) { c.drawLine(0f, gy, w, gy, paint); gy += cell }
        // novos batimentos
        if (beatQueue.isEmpty() && (beat || now - lastPulse > (if (idle) 1100 else 1500))) {
            lastPulse = now
            queuePulse(0.6f + bass * 0.5f)
        }
        repeat(3) {
            sweep[sweepPos] = if (beatQueue.isNotEmpty()) beatQueue.removeFirst() else (rnd.nextFloat() - 0.5f) * 0.01f
            sweepPos = (sweepPos + 1) % cols
        }
        // traçado: mais forte perto da "caneta", apagando para trás, com um vão na frente
        val mid = h * 0.55f
        val amp = h * 0.32f
        paint.strokeCap = Paint.Cap.ROUND
        for (pass in 0 until 2) {
            paint.strokeWidth = (if (pass == 0) 6f else 2f) * density
            for (i in 0 until cols - 1) {
                val age = ((sweepPos - 1 - i) % cols + cols) % cols          // 0 = mais novo
                if (age > cols - 14) continue                               // vão antes da caneta
                val j = (i + 1) % cols
                if (j == sweepPos) continue
                val fade = 1f - age.toFloat() / cols
                paint.color = if (pass == 0) withAlpha(line, (60 * fade).toInt())
                else withAlpha(mix(line, Color.WHITE, 0.2f), (255 * (0.25f + 0.75f * fade)).toInt())
                c.drawLine(i * step, mid - sweep[i] * amp, j * step, mid - sweep[j] * amp, paint)
            }
        }
        // ponto brilhante da caneta
        val head = (sweepPos - 1 + cols) % cols
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawCircle(head * step, mid - sweep[head] * amp, 2.5f * density, paint)
    }

    // ---- Estrada neon: carro visto de trás, luzes passando em alta velocidade ----
    private val streakCount = 150
    private var stTheta = FloatArray(0)
    private var stDist = FloatArray(0)
    private var stColor = FloatArray(0)
    private var roadOffset = 0f

    private fun resetStreak(i: Int, near: Boolean) {
        // a maioria nas "paredes" laterais, algumas no chão e no teto
        val side = rnd.nextInt(10)
        val deg = when {
            side < 4 -> -25f + rnd.nextFloat() * 40f           // direita
            side < 8 -> 165f + rnd.nextFloat() * 40f           // esquerda
            else -> 200f + rnd.nextFloat() * 140f              // teto
        }
        stTheta[i] = Math.toRadians(deg.toDouble()).toFloat()
        stDist[i] = if (near) 0.02f + rnd.nextFloat() * 0.05f else rnd.nextFloat()
        stColor[i] = rnd.nextFloat()
    }

    private fun drawNeonRoad(c: Canvas, n: Int, now: Long) {
        val w = width.toFloat(); val h = areaH
        if (stTheta.size != streakCount) {
            stTheta = FloatArray(streakCount); stDist = FloatArray(streakCount); stColor = FloatArray(streakCount)
            for (i in 0 until streakCount) resetStreak(i, false)
        }
        val avg = avgLevel(n)
        val speed = 0.5f + avg * 2.5f + bass * 2f
        val vpX = w / 2; val vpY = h * 0.42f
        val reach = hypot(w, h) * 0.75f
        paint.color = Color.BLACK
        c.drawRect(0f, 0f, w, h, paint)

        // rastros de luz
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until streakCount) {
            stDist[i] += stDist[i] * speed * frameDt * 1.6f + 0.002f
            if (stDist[i] > 1.1f) resetStreak(i, true)
            val d0 = stDist[i] * reach
            val d1 = d0 * (1.25f + speed * 0.12f)
            val ca = cos(stTheta[i]); val sa = sin(stTheta[i])
            paint.color = colorF(stColor[i], 0.5f + stColor[i] * 0.5f)
            paint.alpha = (stDist[i].coerceIn(0f, 1f) * 230).toInt()
            paint.strokeWidth = (1f + stDist[i] * 7f) * density
            c.drawLine(vpX + ca * d0, vpY + sa * d0 * 0.6f, vpX + ca * d1, vpY + sa * d1 * 0.6f, paint)
        }
        paint.style = Paint.Style.FILL

        // estrada
        path.reset()
        path.moveTo(vpX - w * 0.02f, vpY)
        path.lineTo(vpX + w * 0.02f, vpY)
        path.lineTo(w * 1.1f, h)
        path.lineTo(-w * 0.1f, h)
        path.close()
        paint.shader = LinearGradient(0f, vpY, 0f, h, intArrayOf(0xFF05050A.toInt(), 0xFF101018.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
        paint.shader = null
        // faixas do meio passando
        roadOffset = (roadOffset + frameDt * speed * 0.8f) % 1f
        paint.color = 0x55FFFFFF
        for (k in 0 until 8) {
            val t0 = ((k + roadOffset) / 8f).pow(2f)
            val t1 = ((k + roadOffset + 0.45f) / 8f).pow(2f)
            val y0 = vpY + (h - vpY) * t0; val y1 = vpY + (h - vpY) * min(1f, t1)
            val hw0 = 1f + t0 * 5f * density; val hw1 = 1f + t1 * 5f * density
            path.reset()
            path.moveTo(vpX - hw0, y0); path.lineTo(vpX + hw0, y0)
            path.lineTo(vpX + hw1, y1); path.lineTo(vpX - hw1, y1); path.close()
            c.drawPath(path, paint)
        }

        // carro (silhueta original, vista de trás)
        val cw = min(w * 0.5f, h * 0.55f)
        val ch = cw * 0.42f
        val cx = w / 2
        val bottom = h * 0.9f - bass * 3f * density
        val top = bottom - ch
        // reflexo vermelho no chão
        val lightGlow = (150 + bass * 105).toInt().coerceAtMost(255)
        paint.shader = LinearGradient(0f, bottom, 0f, h, intArrayOf(withAlpha(0xFFFF1A1A.toInt(), lightGlow / 2), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(cx - cw * 0.45f, bottom, cx + cw * 0.45f, h, paint)
        paint.shader = null
        // pneus
        paint.color = 0xFF050505.toInt()
        c.drawRoundRect(cx - cw * 0.48f, bottom - ch * 0.25f, cx - cw * 0.3f, bottom + ch * 0.05f, 6f, 6f, paint)
        c.drawRoundRect(cx + cw * 0.3f, bottom - ch * 0.25f, cx + cw * 0.48f, bottom + ch * 0.05f, 6f, 6f, paint)
        // carroceria
        path.reset()
        path.moveTo(cx - cw * 0.5f, bottom - ch * 0.12f)
        path.lineTo(cx - cw * 0.5f, top + ch * 0.48f)
        path.lineTo(cx - cw * 0.34f, top + ch * 0.36f)
        path.lineTo(cx - cw * 0.24f, top)                       // teto
        path.lineTo(cx + cw * 0.24f, top)
        path.lineTo(cx + cw * 0.34f, top + ch * 0.36f)
        path.lineTo(cx + cw * 0.5f, top + ch * 0.48f)
        path.lineTo(cx + cw * 0.5f, bottom - ch * 0.12f)
        path.close()
        paint.color = 0xFF0B0B14.toInt()
        c.drawPath(path, paint)
        // contorno iluminado pelas luzes do túnel
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = withAlpha(colorF(0.8f, 0.5f), 120)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        // vidro traseiro
        paint.color = 0xFF16161F.toInt()
        c.drawRect(cx - cw * 0.2f, top + ch * 0.08f, cx + cw * 0.2f, top + ch * 0.3f, paint)
        // lanternas (acendem mais forte nos graves)
        val ly = top + ch * 0.55f
        val lh = ch * 0.1f
        for (side in listOf(-1f, 1f)) {
            for (k in 0 until 3) {
                val x0 = cx + side * (cw * 0.12f + k * cw * 0.1f)
                val x1 = x0 + side * cw * 0.08f
                paint.color = withAlpha(0xFFFF2020.toInt(), lightGlow / 3)
                c.drawRect(min(x0, x1) - lh, ly - lh, max(x0, x1) + lh, ly + lh * 2, paint)
                paint.color = withAlpha(0xFFFF3030.toInt(), lightGlow)
                c.drawRect(min(x0, x1), ly, max(x0, x1), ly + lh, paint)
            }
        }
    }
}
