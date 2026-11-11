package com.tecladovoz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val green = Color.parseColor("#00E676")
    private val dim = Color.parseColor("#0B3D1E")
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvKeyboard: TextView
    private lateinit var tvMic: TextView
    private lateinit var tvModel: TextView
    private lateinit var btnDownload: Button
    private lateinit var btnLang: Button
    private val modelButtons = LinkedHashMap<String, Button>()

    @Volatile
    private var downloadingId: String? = null

    @Volatile
    private var progressText = ""

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(24), dp(18), dp(40))
        scroll.addView(col)
        setContentView(scroll)

        addText(col, "Teclado Voz", 26f, true)
        val msg = intent.getStringExtra("msg")
        if (msg != null) addText(col, "⚠ $msg", 15f, true)

        addText(col, "1. Activar el teclado", 17f, true)
        tvKeyboard = addText(col, "", 14f, false)
        addButton(col, "Abrir ajustes de teclados") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        addButton(col, "Elegir Teclado Voz como teclado activo") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        addText(col, "2. Permiso del micrófono", 17f, true)
        tvMic = addText(col, "", 14f, false)
        addButton(col, "Dar permiso del micrófono") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        addText(col, "3. Modelo de Whisper (se descarga una sola vez)", 17f, true)
        val mrow = LinearLayout(this)
        mrow.orientation = LinearLayout.HORIZONTAL
        col.addView(mrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (m in Models.all) {
            val b = Button(this)
            b.text = m.id
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            b.setOnClickListener {
                Cfg.setModel(this, m.id)
                refresh()
            }
            mrow.addView(b, lp)
            modelButtons[m.id] = b
        }
        tvModel = addText(col, "", 14f, false)
        btnDownload = addButton(col, "") { download(Cfg.model(this)) }

        addText(col, "4. Ajustes", 17f, true)
        val tvSize = addText(col, "Tamaño de las teclas: ${Cfg.keyHeight(this)} dp", 14f, false)
        val sbSize = SeekBar(this)
        sbSize.max = 36
        sbSize.progress = Cfg.keyHeight(this) - 36
        tint(sbSize)
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Cfg.setKeyHeight(this@MainActivity, p + 36)
                tvSize.text = "Tamaño de las teclas: ${p + 36} dp"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sbSize)

        val tvThreads = addText(col, "Hilos de CPU para transcribir: ${Cfg.threads(this)}", 14f, false)
        val sbThreads = SeekBar(this)
        sbThreads.max = 7
        sbThreads.progress = Cfg.threads(this) - 1
        tint(sbThreads)
        sbThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Cfg.setThreads(this@MainActivity, p + 1)
                tvThreads.text = "Hilos de CPU para transcribir: ${p + 1}"
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sbThreads)

        btnLang = addButton(col, "") {
            val next = when (Cfg.lang(this)) {
                "es" -> "en"
                "en" -> "auto"
                else -> "es"
            }
            Cfg.setLang(this, next)
            refresh()
        }

        addText(col, "5. Prueba el teclado aquí", 17f, true)
        val et = EditText(this)
        et.setTextColor(green)
        et.setHintTextColor(Color.parseColor("#3C8D5A"))
        et.hint = "Escribe o dicta aquí…"
        et.minLines = 3
        et.gravity = Gravity.TOP
        et.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.background = border(false)
        et.setPadding(dp(12), dp(12), dp(12), dp(12))
        col.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------------------------------------------------------------- utilidades de UI

    private fun border(selected: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(8).toFloat()
        g.setColor(if (selected) green else Color.BLACK)
        g.setStroke(dp(1).coerceAtLeast(1), if (selected) green else dim)
        return g
    }

    private fun styleBtn(b: Button, selected: Boolean) {
        b.background = border(selected)
        b.setTextColor(if (selected) Color.BLACK else green)
        b.isAllCaps = false
    }

    private fun addText(parent: LinearLayout, t: String, sp: Float, bold: Boolean): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = sp
        tv.setTextColor(green)
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(if (bold) 18 else 6)
        parent.addView(tv, lp)
        return tv
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        styleBtn(b, false)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        parent.addView(b, lp)
        return b
    }

    private fun tint(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(green)
        s.thumbTintList = ColorStateList.valueOf(green)
    }

    private fun langName(code: String): String = when (code) {
        "es" -> "Español"
        "en" -> "Inglés"
        else -> "Automático"
    }

    private fun refresh() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        tvKeyboard.text = (if (enabled) "✓ Activado" else "✗ Sin activar") +
                "   |   " + (if (selected) "✓ En uso" else "✗ No es el teclado en uso")

        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        tvMic.text = if (granted) "✓ Permiso concedido" else "✗ Sin permiso"

        val sel = Cfg.model(this)
        for ((id, b) in modelButtons) styleBtn(b, id == sel)
        val info = Models.info(sel)
        val exists = Models.file(this, sel).exists()
        val dl = downloadingId
        tvModel.text = "Modelo ${info.id} (${info.size}): " + when {
            dl == sel -> progressText
            exists -> "descargado ✓"
            else -> "sin descargar"
        }
        btnDownload.isEnabled = dl == null && !exists
        btnDownload.alpha = if (btnDownload.isEnabled) 1f else 0.5f
        btnDownload.text = if (exists) "Modelo listo ✓" else "Descargar modelo ${info.id}"
        btnLang.text = "Idioma del dictado: ${langName(Cfg.lang(this))} (toca para cambiar)"
    }

    // ---------------------------------------------------------------- descarga del modelo

    private fun download(id: String) {
        if (downloadingId != null) return
        downloadingId = id
        progressText = "descargando 0%"
        refresh()
        Thread {
            val dest = Models.file(this, id)
            val part = File(dest.path + ".part")
            var error: String? = null
            try {
                dest.parentFile?.mkdirs()
                val conn = URL(Models.url(id)).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                progressText = if (pct >= 0) "descargando $pct%" else "descargando ${done / 1024 / 1024} MB"
                                ui.post { refresh() }
                            }
                        }
                    }
                }
                if (total > 0 && part.length() != total) throw IOException("descarga incompleta")
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("no se pudo guardar el archivo")
            } catch (e: Exception) {
                error = e.message ?: "error desconocido"
                part.delete()
            }
            downloadingId = null
            ui.post {
                refresh()
                if (error != null) {
                    Toast.makeText(this, "Error al descargar: $error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
