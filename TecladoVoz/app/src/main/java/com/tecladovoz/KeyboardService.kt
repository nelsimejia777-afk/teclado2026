package com.tecladovoz

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class KeyboardService : InputMethodService() {

    private companion object {
        val GREEN: Int = Color.parseColor("#00E676")
        val GREEN_DIM: Int = Color.parseColor("#0B3D1E")
        val GREEN_PRESS: Int = Color.parseColor("#12522A")
        const val SHIFT_OFF = 0
        const val SHIFT_ON = 1
        const val SHIFT_LOCK = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    private var statusView: TextView? = null
    private var micKey: TextView? = null
    private var shiftKey: TextView? = null
    private var symKey: TextView? = null
    private val charKeys = ArrayList<ArrayList<TextView>>()
    private var builtHeight = -1

    private var symbols = false
    private var shiftState = SHIFT_OFF
    private var lastShiftTap = 0L

    private var recorder: SegmentRecorder? = null
    private var recording = false
    private var stopping = false
    private var message = ""

    private val pendingText = StringBuilder()
    private var pendingTime = 0L

    // Solo se tocan desde el hilo "worker"
    private var ctx = 0L
    private var loadedId = ""

    private val letters = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )
    private val syms = listOf(
        listOf("@", "#", "\$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?", "¡", "¿", "%"),
        listOf("~", "<", ">", "=", "[", "]", "|")
    )

    // ---------------------------------------------------------------- ciclo de vida

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = buildKeyboard()

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (builtHeight != Cfg.keyHeight(this)) setInputView(buildKeyboard())
        symbols = false
        shiftState = SHIFT_OFF
        updateAutoShift()
        refreshKeys()
        flushPendingText()
        preloadModel()
        refreshStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRecording()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        recorder?.stop()
        recorder = null
        try {
            worker.execute {
                if (ctx != 0L) {
                    WhisperLib.freeContext(ctx)
                    ctx = 0L
                }
            }
            worker.shutdown()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- interfaz

    @SuppressLint("ClickableViewAccessibility")
    private fun buildKeyboard(): View {
        val d = resources.displayMetrics.density
        val h = Cfg.keyHeight(this)
        builtHeight = h
        charKeys.clear()
        val rowH = (h * d).toInt()
        val pad = (3 * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(pad, 0, pad, pad * 2)

        val st = TextView(this)
        st.setTextColor(GREEN)
        st.textSize = 12f
        st.gravity = Gravity.CENTER
        root.addView(
            st,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (22 * d).toInt())
        )
        statusView = st

        // Fila de números (siempre visible)
        val numRow = newRow(root, rowH)
        for (c in "1234567890") {
            val k = newKey(c.toString(), 1f, 20f)
            attach(k) { typeText(k.text.toString()) }
            numRow.addView(k)
        }

        // Tres filas de letras / símbolos
        for (ri in 0..2) {
            val row = newRow(root, rowH)
            val keys = ArrayList<TextView>()
            if (ri == 2) {
                val sk = newKey("⇧", 1.5f, 20f)
                attach(sk) { onShift() }
                row.addView(sk)
                shiftKey = sk
            }
            for (i in letters[ri].indices) {
                val k = newKey(letters[ri][i], 1f, 22f)
                attach(k, onLong = { longPressChar(k) }) { typeText(k.text.toString()) }
                row.addView(k)
                keys.add(k)
            }
            if (ri == 2) {
                val bk = newKey("⌫", 1.5f, 20f)
                attach(bk, repeat = true) { backspace() }
                row.addView(bk)
            }
            charKeys.add(keys)
        }

        // Fila inferior
        val bottom = newRow(root, rowH)
        val sym = newKey("?123", 1.3f, 15f)
        attach(sym) { toggleSymbols() }
        symKey = sym
        val globe = newKey("🌐", 1f, 18f)
        attach(globe) { showPicker() }
        val mic = newKey("🎤", 1.8f, 22f)
        attach(mic) { toggleMic() }
        micKey = mic
        val comma = newKey(",", 0.9f, 22f)
        attach(comma) { typeText(",") }
        val space = newKey("espacio", 3f, 13f)
        attach(space) { typeText(" ") }
        val dot = newKey(".", 0.9f, 22f)
        attach(dot) { typeText(".") }
        val enter = newKey("↵", 1.3f, 20f)
        attach(enter) { pressEnter() }
        for (k in listOf(sym, globe, mic, comma, space, dot, enter)) bottom.addView(k)

        refreshKeys()
        refreshMicUi()
        refreshStatus()
        return root
    }

    private fun newRow(parent: LinearLayout, rowH: Int): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        parent.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
        )
        return row
    }

    private fun newKey(label: String, weight: Float, sizeSp: Float): TextView {
        val d = resources.displayMetrics.density
        val tv = TextView(this)
        tv.text = label
        tv.textSize = sizeSp
        tv.setTextColor(GREEN)
        tv.gravity = Gravity.CENTER
        tv.setSingleLine(true)
        tv.includeFontPadding = false
        tv.setPadding(0, 0, 0, 0)
        tv.background = keyBg(false)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        val m = (2 * d).toInt()
        lp.setMargins(m, m, m, m)
        tv.layoutParams = lp
        return tv
    }

    private fun keyBg(filled: Boolean): StateListDrawable {
        val d = resources.displayMetrics.density
        fun shape(fill: Int, stroke: Int): GradientDrawable {
            val g = GradientDrawable()
            g.shape = GradientDrawable.RECTANGLE
            g.cornerRadius = 8 * d
            g.setColor(fill)
            g.setStroke(d.toInt().coerceAtLeast(1), stroke)
            return g
        }

        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), shape(GREEN_PRESS, GREEN))
        sl.addState(intArrayOf(), shape(if (filled) GREEN else Color.BLACK, GREEN_DIM))
        return sl
    }

    /** Tecla táctil: pulsar = acción; mantener = acción larga; repeat = repetir al mantener. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attach(
        v: View,
        repeat: Boolean = false,
        onLong: (() -> Unit)? = null,
        onTap: () -> Unit
    ) {
        var longFired = false
        val longRun = Runnable {
            longFired = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLong?.invoke()
        }
        val repRun = object : Runnable {
            override fun run() {
                onTap()
                main.postDelayed(this, 50)
            }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    longFired = false
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (repeat) {
                        onTap()
                        main.postDelayed(repRun, 400)
                    } else if (onLong != null) {
                        main.postDelayed(longRun, 380)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    main.removeCallbacks(longRun)
                    main.removeCallbacks(repRun)
                    if (!repeat && !longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    main.removeCallbacks(longRun)
                    main.removeCallbacks(repRun)
                }
            }
            true
        }
    }

    private fun refreshKeys() {
        val table = if (symbols) syms else letters
        val upper = !symbols && shiftState != SHIFT_OFF
        for (ri in charKeys.indices) {
            for (i in charKeys[ri].indices) {
                val base = table[ri][i]
                charKeys[ri][i].text = if (upper) base.uppercase() else base
            }
        }
        symKey?.text = if (symbols) "ABC" else "?123"
        shiftKey?.alpha = if (symbols) 0.3f else 1f
        refreshShiftUi()
    }

    private fun refreshShiftUi() {
        val k = shiftKey ?: return
        when (shiftState) {
            SHIFT_OFF -> {
                k.text = "⇧"
                k.background = keyBg(false)
                k.setTextColor(GREEN)
            }
            SHIFT_ON -> {
                k.text = "⇧"
                k.background = keyBg(true)
                k.setTextColor(Color.BLACK)
            }
            else -> {
                k.text = "⇧⇧"
                k.background = keyBg(true)
                k.setTextColor(Color.BLACK)
            }
        }
    }

    private fun refreshMicUi() {
        val k = micKey ?: return
        if (recording) {
            k.text = "■"
            k.background = keyBg(true)
            k.setTextColor(Color.BLACK)
        } else {
            k.text = "🎤"
            k.background = keyBg(false)
            k.setTextColor(GREEN)
        }
    }

    private fun refreshStatus() {
        val tv = statusView ?: return
        val busy = pending.get() > 0
        tv.text = when {
            recording -> if (busy) "● Escuchando · transcribiendo…" else "● Escuchando…"
            stopping || busy -> "Transcribiendo…"
            else -> message
        }
    }

    private fun showMessage(msg: String) {
        message = msg
        refreshStatus()
        main.postDelayed({
            if (message == msg) {
                message = ""
                refreshStatus()
            }
        }, 4000)
    }

    // ---------------------------------------------------------------- escritura

    private fun typeText(s: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(s, 1)
        val before = shiftState
        if (shiftState == SHIFT_ON) shiftState = SHIFT_OFF
        updateAutoShift()
        if (before != shiftState) refreshKeys()
    }

    private fun backspace() {
        if (currentInputConnection == null) return
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        val before = shiftState
        updateAutoShift()
        if (before != shiftState) refreshKeys()
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = if (ei != null) ei.imeOptions and EditorInfo.IME_MASK_ACTION else EditorInfo.IME_ACTION_NONE
        val noEnterAction = ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        val before = shiftState
        updateAutoShift()
        if (before != shiftState) refreshKeys()
    }

    private fun longPressChar(k: TextView) {
        if (symbols) return
        val t = k.text.toString()
        val acc: String = when (t.lowercase()) {
            "a" -> "á"
            "e" -> "é"
            "i" -> "í"
            "o" -> "ó"
            "u" -> "ú"
            else -> return
        }
        typeText(if (t != t.lowercase()) acc.uppercase() else acc)
    }

    private fun onShift() {
        if (symbols) return
        val now = System.currentTimeMillis()
        shiftState = when {
            shiftState == SHIFT_LOCK -> SHIFT_OFF
            now - lastShiftTap < 350 -> SHIFT_LOCK
            shiftState == SHIFT_ON -> SHIFT_OFF
            else -> SHIFT_ON
        }
        lastShiftTap = now
        refreshKeys()
    }

    private fun toggleSymbols() {
        symbols = !symbols
        refreshKeys()
    }

    private fun updateAutoShift() {
        if (shiftState == SHIFT_LOCK || symbols) return
        val ei = currentInputEditorInfo
        val ic = currentInputConnection
        var on = false
        if (ei != null && ic != null &&
            (ei.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        ) {
            on = ic.getCursorCapsMode(ei.inputType) != 0
        }
        shiftState = if (on) SHIFT_ON else SHIFT_OFF
    }

    private fun showPicker() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- voz

    private fun toggleMic() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (recording || stopping) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openApp("Falta el permiso del micrófono")
            return
        }
        if (!Models.file(this, Cfg.model(this)).exists()) {
            openApp("Falta descargar el modelo de Whisper")
            return
        }
        if (!WhisperLib.ok) {
            showMessage("Error: no se pudo cargar Whisper")
            return
        }
        preloadModel()

        var self: SegmentRecorder? = null
        val rec = SegmentRecorder(
            onSegment = { audio -> submit(audio) },
            onEnded = {
                main.post {
                    if (recorder === self) {
                        recorder = null
                        recording = false
                    }
                    stopping = false
                    refreshMicUi()
                    refreshStatus()
                }
            }
        )
        self = rec
        if (!rec.start()) {
            showMessage("No se pudo abrir el micrófono")
            return
        }
        recorder = rec
        recording = true
        message = ""
        refreshMicUi()
        refreshStatus()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        if (!recording) return
        recording = false
        stopping = true
        recorder = null
        r.stop()
        refreshMicUi()
        refreshStatus()
    }

    /** Envía un trozo de audio a Whisper (cola en orden, un solo hilo). */
    private fun submit(audio: FloatArray) {
        pending.incrementAndGet()
        main.post { refreshStatus() }
        val a = if (audio.size < 17600) audio.copyOf(17600) else audio
        try {
            worker.execute {
                var text = ""
                try {
                    if (ensureModel()) {
                        val bytes = WhisperLib.transcribe(ctx, a, Cfg.threads(this), Cfg.lang(this))
                        if (bytes != null) text = String(bytes, Charsets.UTF_8)
                    }
                } catch (e: Throwable) {
                    main.post { showMessage("Error al transcribir") }
                }
                main.post {
                    pending.decrementAndGet()
                    if (text.isNotBlank()) commitDictation(text)
                    refreshStatus()
                }
            }
        } catch (e: Exception) {
            pending.decrementAndGet()
        }
    }

    /** Solo se llama desde el hilo "worker". */
    private fun ensureModel(): Boolean {
        val id = Cfg.model(this)
        if (ctx != 0L && loadedId == id) return true
        if (ctx != 0L) {
            WhisperLib.freeContext(ctx)
            ctx = 0L
            loadedId = ""
        }
        val f = Models.file(this, id)
        if (!f.exists()) {
            main.post { showMessage("Falta el modelo $id") }
            return false
        }
        ctx = WhisperLib.initContext(f.absolutePath)
        if (ctx == 0L) {
            main.post { showMessage("No se pudo cargar el modelo") }
            return false
        }
        loadedId = id
        return true
    }

    private fun preloadModel() {
        if (!WhisperLib.ok) return
        if (!Models.file(this, Cfg.model(this)).exists()) return
        try {
            worker.execute {
                try {
                    ensureModel()
                } catch (e: Throwable) {
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun cleanText(raw: String): String =
        raw.replace(Regex("\\[[^\\]]*\\]"), "")
            .replace("♪", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun commitDictation(raw: String) {
        var t = cleanText(raw)
        if (t.isEmpty()) return
        val ic = currentInputConnection
        if (ic == null) {
            // El campo se cerró antes de terminar: se guarda unos segundos para el siguiente.
            if (pendingText.isNotEmpty()) pendingText.append(' ')
            pendingText.append(t)
            pendingTime = System.currentTimeMillis()
            return
        }
        val before = ic.getTextBeforeCursor(1, 0)
        if (!before.isNullOrEmpty()) {
            val c = before[0]
            if (!c.isWhitespace() && "([{¿¡\"'".indexOf(c) < 0) t = " $t"
        }
        ic.commitText(t, 1)
        val prev = shiftState
        updateAutoShift()
        if (prev != shiftState) refreshKeys()
    }

    private fun flushPendingText() {
        if (pendingText.isEmpty()) return
        val fresh = System.currentTimeMillis() - pendingTime < 15000
        val txt = pendingText.toString()
        pendingText.setLength(0)
        if (!fresh) return
        currentInputConnection?.commitText(txt, 1)
    }

    private fun openApp(msg: String) {
        showMessage(msg)
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra("msg", msg)
            startActivity(i)
        } catch (e: Exception) {
        }
    }
}
