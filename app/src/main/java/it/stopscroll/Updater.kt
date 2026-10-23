package it.stopscroll

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Scarica dal tuo repository GitHub:
 *  - config/signatures.json  → firme aggiornate
 *  - l'ultima release        → avvisa se c'è una versione più nuova dell'app
 */
object Updater {

    private val exec = Executors.newSingleThreadExecutor()
    private const val STALE_MS = 12 * 60 * 60 * 1000L
    private val REPO_RE = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    fun refreshIfStale(context: Context) {
        if (System.currentTimeMillis() - Prefs(context).remoteFetchedAt > STALE_MS) {
            refresh(context, force = false)
        }
    }

    fun refresh(context: Context, force: Boolean, onResult: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        exec.execute {
            val p = Prefs(app)
            val msg = if (!force && System.currentTimeMillis() - p.remoteFetchedAt < 60_000L) {
                p.lastRefreshMessage
            } else {
                doRefresh(app)
            }
            if (onResult != null) Handler(Looper.getMainLooper()).post { onResult(msg) }
        }
    }

    private fun doRefresh(context: Context): String {
        val p = Prefs(context)
        val repo = p.githubRepo.trim().trim('/')
        if (!REPO_RE.matches(repo)) return "Nessun repository GitHub impostato"

        val parts = ArrayList<String>()
        try {
            val cfg = httpGet("https://raw.githubusercontent.com/$repo/main/config/signatures.json")
            Signatures.parse(cfg) // verifica che il formato sia valido prima di salvarlo
            p.remoteJson = cfg
            Signatures.invalidate()
            parts += "Firme aggiornate"
        } catch (e: Exception) {
            parts += "Firme non scaricate (${e.message})"
        }

        try {
            val rel = JSONObject(httpGet("https://api.github.com/repos/$repo/releases/latest"))
            val tag = rel.optString("tag_name")
            val code = tag.filter { it.isDigit() }.toIntOrNull()
            if (code != null && code > BuildConfig.VERSION_CODE) {
                parts += "nuova versione $tag disponibile"
                if (p.notifiedRelease != tag) {
                    p.notifiedRelease = tag
                    Notifier.appUpdate(context, rel.optString("html_url"), tag)
                }
            } else {
                parts += "app aggiornata"
            }
        } catch (_: Exception) {
            parts += "nessuna release trovata"
        }

        p.remoteFetchedAt = System.currentTimeMillis()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val msg = parts.joinToString(" · ") + " (ore $time)"
        p.lastRefreshMessage = msg
        return msg
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "StopScroll-Android")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

object Notifier {

    private const val CHANNEL = "avvisi"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Avvisi", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** Il blocco funziona solo con i segnali di riserva → proponi di insegnare. */
    fun degraded(context: Context, pkg: String) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TEACH, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, pkg.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context, pkg.hashCode(),
            "${Apps.label(pkg)} è cambiato",
            "Sto ancora bloccando i ${Apps.contentName(pkg)} con il riconoscimento di riserva. " +
                "Tocca qui per insegnarmi la nuova versione: bastano 10 secondi.",
            pi,
        )
    }

    fun appUpdate(context: Context, url: String, tag: String) {
        if (url.isEmpty()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 4242, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context, 4242,
            "Nuova versione di StopScroll ($tag)",
            "Tocca per aprire la pagina e scaricare l'APK aggiornato.",
            pi,
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }
}
