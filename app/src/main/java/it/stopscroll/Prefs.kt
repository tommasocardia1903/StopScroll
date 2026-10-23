package it.stopscroll

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/** Le app sorvegliate. */
object Apps {
    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
    val ALL = listOf(YOUTUBE, INSTAGRAM)

    fun label(pkg: String): String = when (pkg) {
        YOUTUBE -> "YouTube"
        INSTAGRAM -> "Instagram"
        else -> pkg
    }

    fun contentName(pkg: String): String = when (pkg) {
        YOUTUBE -> "Shorts"
        INSTAGRAM -> "Reels"
        else -> "contenuti"
    }
}

/** Tutte le impostazioni salvate dell'app. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("stopscroll", Context.MODE_PRIVATE)

    // --- Blocco ---
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    fun appEnabled(pkg: String): Boolean = sp.getBoolean("app_$pkg", true)
    fun setAppEnabled(pkg: String, v: Boolean) = sp.edit().putBoolean("app_$pkg", v).apply()

    /** Torna indietro quando si apre uno Short/Reel. */
    var modeBack: Boolean
        get() = sp.getBoolean(K_MODE_BACK, true)
        set(v) = sp.edit().putBoolean(K_MODE_BACK, v).apply()

    /** Copre il pulsante Shorts/Reels nella barra in basso. */
    var modeCover: Boolean
        get() = sp.getBoolean(K_MODE_COVER, true)
        set(v) = sp.edit().putBoolean(K_MODE_COVER, v).apply()

    // --- Fascia oraria ---
    var scheduleOn: Boolean
        get() = sp.getBoolean(K_SCHEDULE_ON, false)
        set(v) = sp.edit().putBoolean(K_SCHEDULE_ON, v).apply()

    var scheduleFrom: Int
        get() = sp.getInt(K_SCHEDULE_FROM, 9)
        set(v) = sp.edit().putInt(K_SCHEDULE_FROM, v.coerceIn(0, 23)).apply()

    var scheduleTo: Int
        get() = sp.getInt(K_SCHEDULE_TO, 18)
        set(v) = sp.edit().putInt(K_SCHEDULE_TO, v.coerceIn(0, 23)).apply()

    fun isActiveNow(): Boolean {
        if (!enabled) return false
        if (!scheduleOn) return true
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val from = scheduleFrom
        val to = scheduleTo
        return when {
            from == to -> true
            from < to -> h in from until to
            else -> h >= from || h < to // fascia a cavallo della mezzanotte
        }
    }

    fun shouldBlock(pkg: String): Boolean = isActiveNow() && appEnabled(pkg)

    // --- GitHub / firme remote ---
    var githubRepo: String
        get() = sp.getString(K_REPO, "") ?: ""
        set(v) = sp.edit().putString(K_REPO, v.trim()).apply()

    var remoteJson: String?
        get() = sp.getString(K_REMOTE_JSON, null)
        set(v) = sp.edit().putString(K_REMOTE_JSON, v).apply()

    var remoteFetchedAt: Long
        get() = sp.getLong(K_REMOTE_AT, 0L)
        set(v) = sp.edit().putLong(K_REMOTE_AT, v).apply()

    var lastRefreshMessage: String
        get() = sp.getString(K_REFRESH_MSG, "") ?: ""
        set(v) = sp.edit().putString(K_REFRESH_MSG, v).apply()

    var notifiedRelease: String
        get() = sp.getString(K_NOTIFIED_RELEASE, "") ?: ""
        set(v) = sp.edit().putString(K_NOTIFIED_RELEASE, v).apply()

    // --- Firme imparate con "Insegna" ---
    fun learnedIds(pkg: String): List<String> =
        (sp.getString("learned_$pkg", "") ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun addLearnedIds(pkg: String, ids: List<String>) {
        val merged = (ids + learnedIds(pkg)).distinct().take(MAX_LEARNED)
        sp.edit().putString("learned_$pkg", merged.joinToString("\n")).apply()
    }

    fun clearLearned() {
        val e = sp.edit()
        for (pkg in Apps.ALL) e.remove("learned_$pkg")
        e.apply()
    }

    // --- Versioni delle app sorvegliate ---
    fun lastVersion(pkg: String): String? = sp.getString("ver_$pkg", null)
    fun setLastVersion(pkg: String, v: String) = sp.edit().putString("ver_$pkg", v).apply()

    /** true = l'app si è aggiornata e il riconoscimento tramite ID non è ancora stato confermato. */
    fun verifyPending(pkg: String): Boolean = sp.getBoolean("verify_$pkg", false)
    fun setVerifyPending(pkg: String, v: Boolean) {
        if (verifyPending(pkg) != v) sp.edit().putBoolean("verify_$pkg", v).apply()
    }

    fun degradedNotified(pkg: String, ver: String): Boolean =
        sp.getString("degraded_$pkg", null) == ver

    fun markDegradedNotified(pkg: String, ver: String) =
        sp.edit().putString("degraded_$pkg", ver).apply()

    // --- Statistiche e diagnostica ---
    var blockCount: Int
        get() = sp.getInt(K_BLOCK_COUNT, 0)
        set(v) = sp.edit().putInt(K_BLOCK_COUNT, v).apply()

    var lastDiag: String
        get() = sp.getString(K_DIAG, "") ?: ""
        set(v) = sp.edit().putString(K_DIAG, v).apply()

    companion object {
        const val K_ENABLED = "enabled"
        const val K_MODE_BACK = "mode_back"
        const val K_MODE_COVER = "mode_cover"
        const val K_SCHEDULE_ON = "schedule_on"
        const val K_SCHEDULE_FROM = "schedule_from"
        const val K_SCHEDULE_TO = "schedule_to"
        const val K_REPO = "github_repo"
        const val K_REMOTE_JSON = "remote_json"
        const val K_REMOTE_AT = "remote_at"
        const val K_REFRESH_MSG = "refresh_msg"
        const val K_NOTIFIED_RELEASE = "notified_release"
        const val K_BLOCK_COUNT = "block_count"
        const val K_DIAG = "diag"
        private const val MAX_LEARNED = 12
    }
}
