package it.stopscroll

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

class BlockerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BlockerService? = null
            private set

        private const val EVAL_DELAY_MS = 250L
        private const val BACK_COOLDOWN_MS = 700L
        private const val VERSION_CHECK_MS = 60_000L
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val overlays = OverlayManager(this)
    private var evalScheduled = false
    private var teach: Teacher? = null

    private var lastBack = 0L
    private var lastToast = 0L
    private val backTimes = ArrayDeque<Long>()
    private val versionCheckedAt = HashMap<String, Long>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key == Prefs.K_REMOTE_JSON || key.startsWith("learned_")) Signatures.invalidate()
        scheduleEval()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        Notifier.ensureChannel(this)
        Updater.refreshIfStale(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in Apps.ALL || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleEval()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        overlays.clear()
        teach?.finish()
        teach = null
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (instance === this) instance = null
    }

    // ---------------------------------------------------------------------

    private fun scheduleEval() {
        if (evalScheduled) return
        evalScheduled = true
        handler.postDelayed({
            evalScheduled = false
            try {
                evaluate()
            } catch (_: Exception) {
                // Mai far crashare il servizio per una schermata strana.
            }
        }, EVAL_DELAY_MS)
    }

    private fun evaluate() {
        val root = rootInActiveWindow
        try {
            val pkg = root?.packageName?.toString()
            if (root == null || pkg == null || pkg !in Apps.ALL) {
                overlays.clear()
                return
            }
            checkAppVersion(pkg)

            if (teach?.pkg == pkg || !prefs.shouldBlock(pkg)) {
                overlays.clear()
                return
            }

            val snap = Snapshot.take(root) ?: return
            val d = Detector.analyze(snap, Signatures.get(this, pkg))

            if (prefs.modeCover) overlays.show(d.tabs) else overlays.clear()

            if (d.isContent) onContentDetected(pkg, snap, d)
        } finally {
            recycleCompat(root)
        }
    }

    private fun onContentDetected(pkg: String, snap: Snapshot, d: Detection) {
        if (d.idMatch) {
            prefs.setVerifyPending(pkg, false)
        } else if (d.degraded) {
            val ver = prefs.lastVersion(pkg) ?: "?"
            if (!prefs.degradedNotified(pkg, ver)) {
                prefs.markDegradedNotified(pkg, ver)
                prefs.lastDiag = "Riconoscimento di riserva su ${Apps.label(pkg)} $ver (${d.reason})\n" +
                    "Elementi a schermo:\n${Diagnostics.describe(snap)}"
                Notifier.degraded(this, pkg)
            }
        }
        if (prefs.modeBack) kick(pkg)
    }

    /** Torna indietro; se si resta bloccati in un ciclo, va alla schermata Home del telefono. */
    private fun kick(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack < BACK_COOLDOWN_MS) return
        lastBack = now

        backTimes.addLast(now)
        while (backTimes.isNotEmpty() && now - backTimes.first() > 5_000) backTimes.removeFirst()

        if (backTimes.size >= 4) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            backTimes.clear()
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        prefs.blockCount = prefs.blockCount + 1

        if (now - lastToast > 4_000) {
            lastToast = now
            Toast.makeText(this, "${Apps.contentName(pkg)} bloccati", Toast.LENGTH_SHORT).show()
        }
        // Ricontrolla subito dopo il "indietro"
        scheduleEval()
    }

    /** Se YouTube/Instagram si sono aggiornati, scarica subito le firme più recenti. */
    private fun checkAppVersion(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val last = versionCheckedAt[pkg]
        if (last != null && now - last < VERSION_CHECK_MS) return
        versionCheckedAt[pkg] = now

        val ver = AppVersions.get(this, pkg) ?: return
        val old = prefs.lastVersion(pkg)
        if (old != ver) {
            prefs.setLastVersion(pkg, ver)
            if (old != null) {
                prefs.setVerifyPending(pkg, true)
                Updater.refresh(this, force = true)
            }
        }
    }

    // --- Usato dalla modalità "Insegna" ------------------------------------

    fun startTeach(pkg: String) {
        teach?.finish()
        overlays.clear()
        val t = Teacher(this, pkg) { teach = null }
        teach = t
        t.start()
    }

    /** Fotografa la finestra di [pkg], anche se in primo piano c'è la nostra bolla. */
    fun snapshotOf(pkg: String): Snapshot? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == pkg) {
            try {
                return Snapshot.take(active)
            } finally {
                recycleCompat(active)
            }
        }
        recycleCompat(active)
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r: AccessibilityNodeInfo = w.root ?: continue
            try {
                if (r.packageName?.toString() == pkg) return Snapshot.take(r)
            } finally {
                recycleCompat(r)
            }
        }
        return null
    }
}

object AppVersions {
    fun get(context: Context, pkg: String): String? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (_: Exception) {
        null
    }
}
