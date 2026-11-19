package com.exemplo.lockvisualizer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var preview: VisualizerView
    private lateinit var status: TextView
    private lateinit var enableSwitch: Switch

    companion object { private const val PICK_IMAGE = 7 }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Prefs.load(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        col.addView(TextView(this).apply {
            text = "Visualizador na Tela de Bloqueio"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "Toque uma música no Spotify, YouTube Music ou qualquer app para ver a prévia."
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(0, dp(4), 0, dp(12))
        })

        preview = VisualizerView(this).apply { setBackgroundColor(Color.BLACK) }
        col.addView(preview, LinearLayout.LayoutParams(-1, dp(240)))

        enableSwitch = Switch(this).apply {
            text = "Mostrar na tela de bloqueio"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = s.enabled
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                Prefs.putBool(this@MainActivity, Prefs.K_ENABLED, on)
                if (on) {
                    ensurePermissions()
                    MusicWatchService.start(this@MainActivity)
                } else {
                    MusicWatchService.stop(this@MainActivity)
                }
            }
        }
        col.addView(enableSwitch)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(status)

        col.addView(label("Estilo"))
        col.addView(radioGroup(Prefs.STYLES, s.style) { Prefs.putInt(this, Prefs.K_STYLE, it); preview.reload() })

        col.addView(label("Texto do estilo \"Texto neon\" (use | para quebrar linha)"))
        col.addView(EditText(this).apply {
            setText(s.neonText)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    Prefs.putString(this@MainActivity, Prefs.K_NEON, e?.toString().orEmpty())
                    preview.reload()
                }
            })
        })
        col.addView(Button(this).apply {
            text = "Escolher imagem/GIF (estilo \"Minha imagem/GIF\")"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                startActivityForResult(i, PICK_IMAGE)
            }
        }, LinearLayout.LayoutParams(-1, -2))

        col.addView(label("Cores"))
        col.addView(radioGroup(Prefs.PALETTES, s.palette) { Prefs.putInt(this, Prefs.K_PALETTE, it); preview.reload() })

        col.addView(label("Tom da \"Cor única\""))
        col.addView(seek(0, 360, s.hue.toInt()) { Prefs.putInt(this, Prefs.K_HUE, it); preview.reload() })

        col.addView(label("Quantidade de barras"))
        col.addView(seek(12, 64, s.bars) { Prefs.putInt(this, Prefs.K_BARS, it); preview.reload() })

        col.addView(label("Sensibilidade"))
        col.addView(seek(50, 250, (s.sensitivity * 100).toInt()) { Prefs.putInt(this, Prefs.K_SENS, it); preview.reload() })

        col.addView(check("Mostrar relógio", s.showClock) { Prefs.putBool(this, Prefs.K_CLOCK, it) })
        col.addView(check("Mostrar nome da música e artista", s.showTrack) {
            Prefs.putBool(this, Prefs.K_TRACK, it)
            if (it) askNotificationAccess()
        })
        col.addView(check("Manter a tela acesa enquanto toca (gasta mais bateria)", s.keepScreenOn) {
            Prefs.putBool(this, Prefs.K_SCREEN_ON, it)
        })

        col.addView(check("Girar para horizontal junto com o celular", s.rotate) {
            Prefs.putBool(this, Prefs.K_ROTATE, it)
        })

        col.addView(TextView(this).apply {
            text = "Na tela de bloqueio:\n" +
                "• Deslize 1 dedo para o lado: troca o estilo\n" +
                "• Deslize 1 dedo para cima/baixo: troca as cores\n" +
                "• Deslize 2 dedos para o lado: próxima/anterior música\n" +
                "• Toque duas vezes: sair"
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(0, dp(14), 0, 0)
        })

        col.addView(Button(this).apply {
            text = "Testar tela cheia agora"
            setOnClickListener { startActivity(Intent(this@MainActivity, LockScreenActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        col.addView(Button(this).apply {
            text = "Conceder permissões"
            setOnClickListener { ensurePermissions() }
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        preview.start()
        updateStatus()
        if (Prefs.load(this).enabled) MusicWatchService.start(this)
    }

    override fun onPause() {
        preview.stop()
        super.onPause()
    }

    // ---------- Permissões ----------

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Mais uma permissão")
                .setMessage(
                    "Para a animação abrir sozinha quando a tela apaga, ative " +
                        "\"Sobrepor a outros apps\" para o Lock Visualizer na próxima tela."
                )
                .setPositiveButton("Abrir") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Agora não", null)
                .show()
            return
        }
        if (Prefs.load(this).showTrack) askNotificationAccess()
    }

    private fun askNotificationAccess() {
        if (MediaListener.hasAccess(this)) return
        AlertDialog.Builder(this)
            .setTitle("Nome da música")
            .setMessage(
                "Para mostrar o nome da música e do artista, ative o Lock Visualizer em " +
                    "\"Acesso a notificações\" na próxima tela. O app só lê qual música está tocando.\n\n" +
                    "Se o Android disser \"Configuração restrita\": vá em Configurações > Apps > " +
                    "Lock Visualizer > menu ⋮ > \"Permitir configurações restritas\" e tente de novo."
            )
            .setPositiveButton("Abrir") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    @Deprecated("Activity sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            Prefs.putString(this, Prefs.K_IMAGE, uri.toString())
            preview.reload()
            Toast.makeText(this, "Imagem escolhida! Selecione o estilo \"Minha imagem/GIF\".", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        preview.stop()
        preview.start()
        updateStatus()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ensurePermissions() // segue para a permissão de sobreposição
        }
    }

    private fun updateStatus() {
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val msg = when {
            !audio -> "⚠ Falta permissão de áudio (necessária para captar o ritmo)."
            !preview.isCapturing -> "⚠ Este celular não liberou a captura do áudio."
            !overlay -> "⚠ Falta \"Sobrepor a outros apps\" para abrir sozinho no bloqueio."
            Prefs.load(this).showTrack && !MediaListener.hasAccess(this) ->
                "⚠ Falta \"Acesso a notificações\" para mostrar o nome da música."
            else -> "✓ Tudo pronto."
        }
        status.text = msg
        status.setTextColor(if (msg.startsWith("✓")) 0xFF00E676.toInt() else 0xFFFFC400.toInt())
    }

    // ---------- Pequenos construtores de interface ----------

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(0xFF00E5FF.toInt())
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun radioGroup(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val group = RadioGroup(this)
        val ids = options.map { View.generateViewId() }
        options.forEachIndexed { i, name ->
            group.addView(RadioButton(this).apply {
                id = ids[i]
                text = name
                setTextColor(Color.WHITE)
            })
        }
        group.check(ids[selected.coerceIn(0, options.size - 1)])
        group.setOnCheckedChangeListener { _, checkedId ->
            val idx = ids.indexOf(checkedId)
            if (idx >= 0) onPick(idx)
        }
        return group
    }

    private fun seek(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value.coerceIn(min, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun check(t: String, on: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t
        isChecked = on
        setTextColor(Color.WHITE)
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}
