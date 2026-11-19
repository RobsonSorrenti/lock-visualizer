package com.exemplo.lockvisualizer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
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

        if (s.showClock) {
            // Vertical: relógio grande centralizado. Horizontal: menor, no canto, para sobrar altura às barras.
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            }
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
            val lp = if (landscape) {
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    topMargin = (16 * dp).toInt()
                    leftMargin = (32 * dp).toInt()
                }
            } else {
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = (72 * dp).toInt() }
            }
            root.addView(box, lp)
            // As barras começam logo abaixo do relógio
            box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                vis.contentTop = v.bottom + 12 * dp
            }
        } else {
            vis.contentTop = (if (landscape) 16 else 40) * dp
        }

        root.addView(TextView(this).apply {
            text = "Toque duas vezes para sair"
            textSize = 12f
            setTextColor(0x66FFFFFF)
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = ((if (landscape) 8 else 40) * dp).toInt()
        })

        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < 350) finish()
                lastTap = now
            }
            true
        }

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
        silentChecks = 0
        handler.postDelayed(musicChecker, 2000)
    }

    override fun onPause() {
        // tela apagou ou saiu: para de ler o áudio para economizar bateria
        vis.stop()
        handler.removeCallbacks(musicChecker)
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
