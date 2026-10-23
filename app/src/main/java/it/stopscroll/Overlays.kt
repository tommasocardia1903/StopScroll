package it.stopscroll

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

private fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** Riquadri opachi sopra il pulsante Shorts/Reels: lo nascondono e ne bloccano il tocco. */
class OverlayManager(private val service: AccessibilityService) {

    private val wm: WindowManager by lazy {
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val views = ArrayList<View>()
    private val current = ArrayList<Rect>()

    fun show(rects: List<Rect>) {
        if (rects == current) return
        clear()
        for (r in rects) {
            val v = View(service).apply {
                background = GradientDrawable().apply {
                    setColor(0xF2202020.toInt())
                    cornerRadius = service.dp(12).toFloat()
                }
                setOnClickListener {
                    Toast.makeText(service, "Bloccato da StopScroll", Toast.LENGTH_SHORT).show()
                }
            }
            val lp = overlayParams(r.width(), r.height()).apply {
                x = r.left
                y = r.top
            }
            try {
                wm.addView(v, lp)
                views.add(v)
            } catch (_: Exception) {
            }
        }
        current.clear()
        rects.forEach { current.add(Rect(it)) }
    }

    fun clear() {
        for (v in views) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        views.clear()
        current.clear()
    }
}

/**
 * Bolla flottante della modalità "Insegna".
 * Passo 1: sei su uno Short/Reel → tocca la bolla.
 * Passo 2: sei sulla Home → tocca la bolla. L'app confronta le due schermate.
 * Tieni premuto sulla bolla per annullare.
 */
class Teacher(
    private val service: BlockerService,
    val pkg: String,
    private val onDone: () -> Unit,
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var step = 1
    private var contentSnap: Snapshot? = null
    private var bubble: TextView? = null

    fun start() {
        val tv = TextView(service).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(service.dp(18), service.dp(12), service.dp(18), service.dp(12))
            background = GradientDrawable().apply {
                setColor(0xF0E53935.toInt())
                cornerRadius = service.dp(24).toFloat()
            }
            setOnClickListener { onTap() }
            setOnLongClickListener {
                toast("Insegnamento annullato")
                finish()
                true
            }
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = service.dp(90)
        }
        try {
            wm.addView(tv, lp)
            bubble = tv
        } catch (_: Exception) {
            onDone()
            return
        }
        updateText()
    }

    private fun updateText() {
        val name = Apps.contentName(pkg)
        bubble?.text = if (step == 1) {
            "1/2 · Apri uno dei $name a schermo intero, poi tocca qui"
        } else {
            "2/2 · Ora torna alla Home di ${Apps.label(pkg)}, poi tocca qui"
        }
    }

    private fun onTap() {
        val snap = service.snapshotOf(pkg)
        if (snap == null) {
            toast("Apri prima ${Apps.label(pkg)}")
            return
        }
        if (step == 1) {
            contentSnap = snap
            step = 2
            updateText()
            return
        }
        val content = contentSnap ?: return
        val learned = Learner.learn(content, snap)
        val prefs = Prefs(service)
        prefs.lastDiag = "Insegnamento ${Apps.label(pkg)} ${prefs.lastVersion(pkg) ?: ""}\n" +
            "Imparati: ${learned.joinToString().ifEmpty { "nessuno" }}\n" +
            "Elementi nello Short/Reel:\n${Diagnostics.describe(content)}"
        if (learned.isEmpty()) {
            toast("Non ho trovato differenze utili. Riprova: al passo 1 il contenuto deve essere a schermo intero.")
            step = 1
            contentSnap = null
            updateText()
            return
        }
        prefs.addLearnedIds(pkg, learned)
        prefs.setVerifyPending(pkg, false)
        Signatures.invalidate()
        toast("Imparato! Nuove firme: ${learned.joinToString()}")
        finish()
    }

    fun finish() {
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
        onDone()
    }

    private fun toast(msg: String) = Toast.makeText(service, msg, Toast.LENGTH_LONG).show()
}
