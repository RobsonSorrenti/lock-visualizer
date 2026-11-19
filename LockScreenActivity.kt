package com.exemplo.lockvisualizer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/** Tela que aparece por cima da tela de bloqueio enquanto toca música. */
class LockScreenActivity : Activity() {

    private lateinit var vis: VisualizerView
    private val handler = Handler(Looper.getMainLooper())
    private var silentChecks = 0
    private var lastTap = 0L
    private lateinit var flashView: TextView

    // ---- Gestos ----
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var firstId = 0
    private var maxFingers = 1

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                firstId = e.getPointerId(0)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                maxFingers = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> maxFingers = maxOf(maxFingers, e.pointerCount)
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(firstId)
                if (i >= 0) { lastX = e.getX(i); lastY = e.getY(i) }
            }
            MotionEvent.ACTION_UP -> {
                val dp = resources.displayMetrics.density
                val dx = lastX - downX
                val dy = lastY - downY
                val minSwipe = 70 * dp
                val horizontal = Math.abs(dx) > minSwipe && Math.abs(dx) > Math.abs(dy) * 1.3f
                val vertical = Math.abs(dy) > minSwipe && Math.abs(dy) > Math.abs(dx) * 1.3f
                when {
                    maxFingers >= 2 && horizontal -> skipTrack(next = dx < 0)
                    maxFingers == 1 && horizontal -> cycle(Prefs.K_STYLE, Prefs.STYLES, if (dx < 0) 1 else -1, "Estilo")
                    maxFingers == 1 && vertical -> cycle(Prefs.K_PALETTE, Prefs.PALETTES, if (dy < 0) 1 else -1, "Cores")
                    maxFingers == 1 && Math.abs(dx) < 20 * dp && Math.abs(dy) < 20 * dp -> {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTap < 350) finish()
                        lastTap = now
                    }
                }
            }
        }
    }

    private fun cycle(key: String, names: List<String>, dir: Int, label: String) {
        val s = Prefs.load(this)
        val cur = if (key == Prefs.K_STYLE) s.style else s.palette
        val n = names.size
        val v = ((cur + dir) % n + n) % n
        Prefs.putInt(this, key, v)
        vis.reload()
        flash("$label: ${names[v]}")
    }

    private fun skipTrack(next: Boolean) {
        val c = controller
        if (c != null) {
            if (next) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
        } else {
            // sem "Acesso a notificações": manda o mesmo comando dos botões de fone de ouvido
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val code = if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        silentChecks = 0
        flash(if (next) "⏭  Próxima" else "⏮  Anterior")
    }

    private fun flash(text: String) {
        flashView.text = text
        flashView.animate().cancel()
        flashView.alpha = 1f
        flashView.animate().alpha(0f).setStartDelay(700).setDuration(400)
    }

    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private var showTrack = true
    private var controller: MediaController? = null

    // ---- Nome da música / artista (lido da sessão de mídia do Spotify, YouTube Music etc.) ----
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // se outro app começou a tocar, troca para ele
            refreshSessions()
        }
    }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private fun listenerComponent() = ComponentName(this, MediaListener::class.java)

    private fun startMedia() {
        if (!showTrack || !MediaListener.hasAccess(this)) return
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent())
            pickController(msm.getActiveSessions(listenerComponent()))
        } catch (_: SecurityException) {}
    }

    private fun refreshSessions() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            val list = msm.getActiveSessions(listenerComponent())
            val playing = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (playing != null && playing.sessionToken != controller?.sessionToken) pickController(list)
        } catch (_: SecurityException) {}
    }

    private fun stopMedia() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(mediaCallback)
        controller = null
    }

    private fun pickController(list: List<MediaController>?) {
        controller?.unregisterCallback(mediaCallback)
        val c = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        controller = c
        c?.registerCallback(mediaCallback)
        showMetadata(c?.metadata)
    }

    private fun showMetadata(m: MediaMetadata?) {
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        titleView.text = title
        artistView.text = artist
        titleView.visibility = if (title.isNotEmpty()) View.VISIBLE else View.GONE
        artistView.visibility = if (artist.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // Fecha sozinha quando a música para (3 verificações seguidas sem som ≈ 6 s)
    private val musicChecker = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            silentChecks = if (am.isMusicActive) 0 else silentChecks + 1
            if (silentChecks >= 3) {
                finish()
                return
            }
            handler.postDelayed(this, 2000)
        }
    }

    // Se a pessoa desbloquear (digital, rosto, senha), some da frente
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        val s = Prefs.load(this)
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        vis = VisualizerView(this)
        root.addView(vis, FrameLayout.LayoutParams(-1, -1))

        // Gira com o sensor mesmo que a tela de bloqueio do sistema seja só vertical
        requestedOrientation = if (s.rotate) ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dp = resources.displayMetrics.density

        // Bloco do topo: relógio + data + música (cada parte pode ser desligada nas configurações)
        // Vertical: grande e centralizado. Horizontal: menor, no canto, para sobrar altura às barras.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        }
        if (s.showClock) {
            box.addView(TextClock(this).apply {
                format24Hour = "HH:mm"
                format12Hour = "hh:mm"
                textSize = if (landscape) 34f else 64f
                setTextColor(Color.WHITE)
            })
            box.addView(TextClock(this).apply {
                format24Hour = "EEEE, d 'de' MMMM"
                format12Hour = "EEEE, d 'de' MMMM"
                textSize = if (landscape) 12f else 16f
                setTextColor(0xCCFFFFFF.toInt())
            })
        }
        titleView = TextView(this).apply {
            textSize = if (landscape) 15f else 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt().takeIf { !landscape } ?: 0, if (s.showClock) (14 * dp).toInt() else 0,
                (24 * dp).toInt().takeIf { !landscape } ?: 0, 0)
            visibility = View.GONE
        }
        artistView = TextView(this).apply {
            textSize = if (landscape) 12f else 15f
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt().takeIf { !landscape } ?: 0, 0,
                (24 * dp).toInt().takeIf { !landscape } ?: 0, 0)
            visibility = View.GONE
        }
        box.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        box.addView(artistView, LinearLayout.LayoutParams(-1, -2))

        val lp = if (landscape) {
            FrameLayout.LayoutParams((320 * dp).toInt(), -2, Gravity.TOP or Gravity.START).apply {
                topMargin = (16 * dp).toInt()
                leftMargin = (32 * dp).toInt()
            }
        } else {
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = (72 * dp).toInt() }
        }
        root.addView(box, lp)
        vis.contentTop = (if (landscape) 16 else 40) * dp
        // As barras começam logo abaixo do relógio/música
        box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            vis.contentTop = if (v.height > 0) v.bottom + 12 * dp else (if (landscape) 16 else 40) * dp
        }
        showTrack = s.showTrack

        flashView = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        root.addView(flashView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        val hint = TextView(this).apply {
            text = "Deslize: estilo  •  ↕ cores  •  2 dedos: música  •  toque 2x: sair"
            textSize = 11f
            setTextColor(0x88FFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = ((if (landscape) 8 else 40) * dp).toInt()
        })
        hint.animate().alpha(0.35f).setStartDelay(5000).setDuration(800)

        root.setOnTouchListener { _, e -> handleTouch(e); true }

        setContentView(root)
        hideSystemBars()

        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(unlockReceiver, f)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onResume() {
        super.onResume()
        vis.reload()
        vis.start()
        startMedia()
        silentChecks = 0
        handler.postDelayed(musicChecker, 2000)
    }

    override fun onPause() {
        // tela apagou ou saiu: para de ler o áudio para economizar bateria
        vis.stop()
        stopMedia()
        handler.removeCallbacks(musicChecker)
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
