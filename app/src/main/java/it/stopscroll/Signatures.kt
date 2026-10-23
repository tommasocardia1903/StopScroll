package it.stopscroll

import android.content.Context
import org.json.JSONObject

/** Gli indizi che identificano Shorts/Reels in una specifica app. */
data class AppSignature(
    /** ID degli elementi che esistono solo nel player di Shorts/Reels. */
    val playerIds: Set<String>,
    /** ID del pulsante Shorts/Reels nella barra di navigazione. */
    val tabIds: Set<String>,
    /** Testo/descrizione del pulsante (in minuscolo), es. "shorts". */
    val tabLabels: Set<String>,
    /** Parole dei pulsanti laterali del player (in minuscolo): mi piace, commenti... */
    val actionLabels: Set<String>,
) {
    companion object {
        val EMPTY = AppSignature(emptySet(), emptySet(), emptySet(), emptySet())
    }
}

/**
 * Unisce tre fonti di firme:
 *  1. quelle incluse nell'app (assets/signatures.json)
 *  2. quelle scaricate dal tuo repository GitHub (config/signatures.json)
 *  3. quelle imparate sul telefono con "Insegna"
 */
object Signatures {

    @Volatile
    private var cache: Map<String, AppSignature>? = null

    fun invalidate() {
        cache = null
    }

    fun get(context: Context, pkg: String): AppSignature {
        val all = cache ?: build(context).also { cache = it }
        return all[pkg] ?: AppSignature.EMPTY
    }

    private fun build(context: Context): Map<String, AppSignature> {
        val prefs = Prefs(context)
        val base = runCatching { parse(readAsset(context)) }.getOrDefault(emptyMap())
        val remote = prefs.remoteJson
            ?.let { json -> runCatching { parse(json) }.getOrNull() }
            ?: emptyMap()

        val result = HashMap<String, AppSignature>()
        for (pkg in Apps.ALL) {
            val a = base[pkg] ?: AppSignature.EMPTY
            val r = remote[pkg] ?: AppSignature.EMPTY
            result[pkg] = AppSignature(
                playerIds = a.playerIds + r.playerIds + prefs.learnedIds(pkg),
                tabIds = a.tabIds + r.tabIds,
                tabLabels = a.tabLabels + r.tabLabels,
                actionLabels = a.actionLabels + r.actionLabels,
            )
        }
        return result
    }

    /** Legge il JSON delle firme. Lancia un'eccezione se il formato non è valido. */
    fun parse(json: String): Map<String, AppSignature> {
        val apps = JSONObject(json).getJSONObject("apps")
        val out = HashMap<String, AppSignature>()
        for (key in apps.keys()) {
            val o = apps.getJSONObject(key)
            out[key] = AppSignature(
                playerIds = o.strSet("player_ids"),
                tabIds = o.strSet("tab_ids"),
                tabLabels = o.strSet("tab_labels").map { it.lowercase() }.toSet(),
                actionLabels = o.strSet("action_labels").map { it.lowercase() }.toSet(),
            )
        }
        return out
    }

    private fun JSONObject.strSet(name: String): Set<String> {
        val arr = optJSONArray(name) ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    fun readAsset(context: Context): String =
        context.assets.open("signatures.json").bufferedReader().use { it.readText() }
}
