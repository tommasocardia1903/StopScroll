package it.stopscroll

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Copia "piatta" di un elemento dello schermo, così non teniamo vivi i nodi di sistema. */
data class NodeInfo(
    /** ID breve (la parte dopo ":id/"), es. "reel_recycler". */
    val id: String?,
    val cls: String,
    /** Descrizione + testo, in minuscolo. */
    val label: String,
    val bounds: Rect,
    val scrollable: Boolean,
    /** Righe/colonne dichiarate da liste e pager (-1 = sconosciuto). */
    val rows: Int,
    val cols: Int,
    val parent: Int,
)

/** Fotografia della struttura di una schermata. */
class Snapshot(val pkg: String, val screen: Rect, val nodes: List<NodeInfo>) {

    val ids: Set<String> by lazy { nodes.mapNotNull { it.id }.toSet() }
    val screenArea: Long get() = screen.width().toLong() * screen.height()

    companion object {
        private const val MAX_NODES = 2500

        fun take(root: AccessibilityNodeInfo): Snapshot? {
            val pkg = root.packageName?.toString() ?: return null
            val screen = Rect()
            root.getBoundsInScreen(screen)
            if (screen.width() <= 0 || screen.height() <= 0) return null

            val out = ArrayList<NodeInfo>(256)
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to -1)

            while (stack.isNotEmpty() && out.size < MAX_NODES) {
                val (n, parent) = stack.removeLast()
                val r = Rect()
                n.getBoundsInScreen(r)
                val full = n.viewIdResourceName
                val id = full?.substringAfter(":id/", full)
                val label = buildString {
                    n.contentDescription?.let { append(it) }
                    append(' ')
                    n.text?.let { append(it) }
                }.trim().lowercase()
                val ci = n.collectionInfo
                val index = out.size
                out.add(
                    NodeInfo(
                        id = id,
                        cls = n.className?.toString() ?: "",
                        label = label,
                        bounds = r,
                        scrollable = n.isScrollable,
                        rows = ci?.rowCount ?: -1,
                        cols = ci?.columnCount ?: -1,
                        parent = parent,
                    )
                )
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    stack.addLast(c to index)
                }
                if (n !== root) recycleCompat(n)
            }
            while (stack.isNotEmpty()) {
                val (n, _) = stack.removeLast()
                if (n !== root) recycleCompat(n)
            }
            return Snapshot(pkg, screen, out)
        }
    }
}

@Suppress("DEPRECATION")
fun recycleCompat(n: AccessibilityNodeInfo?) {
    if (n == null || Build.VERSION.SDK_INT >= 33) return
    try {
        n.recycle()
    } catch (_: Exception) {
    }
}

/** Area di [b] effettivamente visibile dentro [screen]. */
fun visibleArea(b: Rect, screen: Rect): Long {
    val r = Rect(b)
    if (!r.intersect(screen)) return 0L
    return r.width().toLong() * r.height()
}

/** Esito dell'analisi di una schermata. */
data class Detection(
    val score: Int,
    /** true se riconosciuto tramite ID (il metodo più preciso). */
    val idMatch: Boolean,
    /** Rettangoli dei pulsanti Shorts/Reels da coprire. */
    val tabs: List<Rect>,
    val reason: String,
) {
    val isContent: Boolean get() = score >= THRESHOLD

    /** Bloccato solo grazie ai segnali di riserva: gli ID vanno probabilmente aggiornati. */
    val degraded: Boolean get() = isContent && !idMatch

    companion object {
        const val THRESHOLD = 3
    }
}

/**
 * Rilevamento a più segnali:
 *  - ID noto del player, visibile per gran parte dello schermo  → +3
 *  - pager verticale con una "pagina" a tutto schermo           → +1
 *  - dentro quella pagina, almeno 2 pulsanti tipo like/commenti → +2
 * Con 3 punti si blocca.
 */
object Detector {

    fun analyze(s: Snapshot, sig: AppSignature): Detection {
        val sh = s.screen.height()
        val sw = s.screen.width()
        val screenArea = s.screenArea
        var score = 0
        val reasons = ArrayList<String>()

        // 1) ID
        val idHit = s.nodes.firstOrNull { n ->
            n.id != null && n.id in sig.playerIds && visibleArea(n.bounds, s.screen) >= screenArea * 4 / 10
        }
        if (idHit != null) {
            score += 3
            reasons += "id:${idHit.id}"
        }

        // 2) Struttura: contenitore scorrevole a tutto schermo, non orizzontale,
        //    con un figlio che occupa praticamente tutto lo schermo (= una "pagina").
        var page: Rect? = null
        for ((i, n) in s.nodes.withIndex()) {
            if (!n.scrollable) continue
            if (n.cols > 1 && n.rows <= 1) continue // orizzontale (es. Storie)
            if (n.bounds.height() < sh * 8 / 10) continue
            if (visibleArea(n.bounds, s.screen) < screenArea * 7 / 10) continue
            val child = s.nodes.firstOrNull { c ->
                c.parent == i && c.bounds.height() >= sh * 8 / 10 &&
                    visibleArea(c.bounds, s.screen) >= screenArea * 7 / 10
            }
            if (child != null) {
                page = child.bounds
                break
            }
        }
        val pg = page
        if (pg != null) {
            score += 1
            reasons += "pagina"
            // 3) Pulsanti laterali dentro la pagina
            val buttons = s.nodes.count { n ->
                n.label.isNotEmpty() && pg.contains(n.bounds) &&
                    sig.actionLabels.any { l -> n.label.contains(l) }
            }
            if (buttons >= 2) {
                score += 2
                reasons += "pulsanti:$buttons"
            }
        }

        // Pulsante Shorts/Reels nella barra in basso
        val bottomLimit = s.screen.top + sh * 3 / 4
        val candidates = s.nodes.filter { n ->
            n.bounds.top >= bottomLimit &&
                n.bounds.height() in 1..(sh / 5) &&
                n.bounds.width() in 1..(sw / 2) &&
                ((n.id != null && n.id in sig.tabIds) || matchesTabLabel(n.label, sig.tabLabels))
        }.map { it.bounds }
        val tabs = ArrayList<Rect>()
        for (r in candidates.sortedByDescending { it.width().toLong() * it.height() }) {
            if (tabs.none { it.contains(r) }) tabs.add(Rect(r))
        }

        return Detection(score, idHit != null, tabs, reasons.joinToString(" "))
    }

    private fun matchesTabLabel(label: String, labels: Set<String>): Boolean {
        if (label.isEmpty()) return false
        return labels.any { l -> label == l || label.startsWith("$l ") || label.startsWith("$l,") }
    }
}

/** Modalità "Insegna": confronta uno Short con la Home e trova gli elementi esclusivi del player. */
object Learner {

    private val KEYWORDS = listOf("reel", "short", "clip")

    fun learn(content: Snapshot, other: Snapshot): List<String> {
        val area = content.screenArea
        val otherIds = other.ids
        return content.nodes
            .filter { it.id != null && it.id !in otherIds }
            .groupBy { it.id!! }
            .map { (id, ns) -> id to ns.maxOf { visibleArea(it.bounds, content.screen) } }
            .filter { it.second >= area / 2 }
            .sortedWith(
                compareByDescending<Pair<String, Long>> { p ->
                    if (KEYWORDS.any { k -> p.first.contains(k, ignoreCase = true) }) 1 else 0
                }.thenByDescending { it.second }
            )
            .take(3)
            .map { it.first }
    }
}

/** Testo leggibile per capire cosa c'era a schermo (utile per correggere le firme a mano). */
object Diagnostics {
    fun describe(s: Snapshot, max: Int = 30): String {
        val area = s.screenArea.coerceAtLeast(1)
        return s.nodes
            .filter { it.id != null }
            .groupBy { it.id!! }
            .map { (id, ns) -> id to ns.maxOf { visibleArea(it.bounds, s.screen) } }
            .sortedByDescending { it.second }
            .take(max)
            .joinToString("\n") { (id, a) -> "  $id (${a * 100 / area}%)" }
    }
}
