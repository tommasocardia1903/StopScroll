package it.stopscroll

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_TEACH = "teach_pkg"
    }

    private lateinit var prefs: Prefs

    private lateinit var serviceStatus: TextView
    private lateinit var btnA11y: Button
    private lateinit var restrictedHint: TextView
    private lateinit var btnAppInfo: Button
    private lateinit var swEnabled: Switch
    private lateinit var swYoutube: Switch
    private lateinit var swInstagram: Switch
    private lateinit var swBack: Switch
    private lateinit var swCover: Switch
    private lateinit var swSchedule: Switch
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var appsStatus: TextView
    private lateinit var etRepo: EditText
    private lateinit var refreshStatus: TextView
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        serviceStatus = findViewById(R.id.serviceStatus)
        btnA11y = findViewById(R.id.btnA11y)
        restrictedHint = findViewById(R.id.restrictedHint)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        swEnabled = findViewById(R.id.swEnabled)
        swYoutube = findViewById(R.id.swYoutube)
        swInstagram = findViewById(R.id.swInstagram)
        swBack = findViewById(R.id.swBack)
        swCover = findViewById(R.id.swCover)
        swSchedule = findViewById(R.id.swSchedule)
        etFrom = findViewById(R.id.etFrom)
        etTo = findViewById(R.id.etTo)
        appsStatus = findViewById(R.id.appsStatus)
        etRepo = findViewById(R.id.etRepo)
        refreshStatus = findViewById(R.id.refreshStatus)
        stats = findViewById(R.id.stats)

        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }

        swEnabled.setOnCheckedChangeListener { _, v ->
            prefs.enabled = v
            BlockTileService.requestUpdate(this)
        }
        swYoutube.setOnCheckedChangeListener { _, v -> prefs.setAppEnabled(Apps.YOUTUBE, v) }
        swInstagram.setOnCheckedChangeListener { _, v -> prefs.setAppEnabled(Apps.INSTAGRAM, v) }
        swBack.setOnCheckedChangeListener { _, v -> prefs.modeBack = v }
        swCover.setOnCheckedChangeListener { _, v -> prefs.modeCover = v }
        swSchedule.setOnCheckedChangeListener { _, v ->
            saveHours()
            prefs.scheduleOn = v
        }

        findViewById<Button>(R.id.btnTeachYt).setOnClickListener { startTeachFlow(Apps.YOUTUBE) }
        findViewById<Button>(R.id.btnTeachIg).setOnClickListener { startTeachFlow(Apps.INSTAGRAM) }
        findViewById<Button>(R.id.btnForget).setOnClickListener {
            prefs.clearLearned()
            Signatures.invalidate()
            toast("Firme imparate cancellate")
            refreshUi()
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            prefs.githubRepo = etRepo.text.toString()
            refreshStatus.text = "Aggiornamento in corso…"
            Updater.refresh(this, force = true) { msg ->
                refreshStatus.text = msg
            }
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareDiagnostics() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        saveHours()
        prefs.githubRepo = etRepo.text.toString()
    }

    private fun handleIntent(intent: Intent?) {
        val pkg = intent?.getStringExtra(EXTRA_TEACH) ?: return
        intent.removeExtra(EXTRA_TEACH)
        startTeachFlow(pkg)
    }

    private fun saveHours() {
        etFrom.text.toString().toIntOrNull()?.let { prefs.scheduleFrom = it }
        etTo.text.toString().toIntOrNull()?.let { prefs.scheduleTo = it }
    }

    private fun refreshUi() {
        val on = isServiceEnabled()
        serviceStatus.text = if (on) "✓ Servizio attivo" else "✗ Servizio di accessibilità non attivo"
        serviceStatus.setBackgroundColor(if (on) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        serviceStatus.setTextColor(Color.WHITE)
        val vis = if (on) View.GONE else View.VISIBLE
        btnA11y.visibility = vis
        restrictedHint.visibility = vis
        btnAppInfo.visibility = vis

        swEnabled.isChecked = prefs.enabled
        swYoutube.isChecked = prefs.appEnabled(Apps.YOUTUBE)
        swInstagram.isChecked = prefs.appEnabled(Apps.INSTAGRAM)
        swBack.isChecked = prefs.modeBack
        swCover.isChecked = prefs.modeCover
        swSchedule.isChecked = prefs.scheduleOn
        etFrom.setText(prefs.scheduleFrom.toString())
        etTo.setText(prefs.scheduleTo.toString())
        etRepo.setText(prefs.githubRepo)
        refreshStatus.text = prefs.lastRefreshMessage

        appsStatus.text = Apps.ALL.joinToString("\n") { pkg ->
            val ver = AppVersions.get(this, pkg)
            val state = when {
                ver == null -> "non installata"
                prefs.verifyPending(pkg) -> "$ver · aggiornata di recente, in verifica"
                else -> ver
            }
            val learned = prefs.learnedIds(pkg).size
            "${Apps.label(pkg)}: $state" + if (learned > 0) " · $learned firme imparate" else ""
        }
        stats.text = "Blocchi totali: ${prefs.blockCount} · StopScroll ${BuildConfig.VERSION_NAME}"
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, BlockerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun startTeachFlow(pkg: String) {
        val svc = BlockerService.instance
        if (svc == null || !isServiceEnabled()) {
            toast("Attiva prima il servizio di accessibilità")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            toast("${Apps.label(pkg)} non è installata")
            return
        }
        svc.startTeach(pkg)
        startActivity(launch)
    }

    private fun shareDiagnostics() {
        val text = buildString {
            appendLine("StopScroll ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}")
            for (pkg in Apps.ALL) {
                appendLine("${Apps.label(pkg)}: ${AppVersions.get(this@MainActivity, pkg) ?: "non installata"}")
                val learned = prefs.learnedIds(pkg)
                if (learned.isNotEmpty()) appendLine("  imparate: ${learned.joinToString()}")
            }
            appendLine("Repository: ${prefs.githubRepo.ifEmpty { "-" }}")
            appendLine("Ultimo aggiornamento: ${prefs.lastRefreshMessage.ifEmpty { "-" }}")
            appendLine()
            append(prefs.lastDiag.ifEmpty { "Nessuna diagnostica registrata." })
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnostica StopScroll")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Condividi diagnostica"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
