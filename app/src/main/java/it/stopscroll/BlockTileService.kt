package it.stopscroll

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Pulsante nella tendina delle Impostazioni rapide: un tocco attiva o mette in pausa il blocco. */
class BlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val p = Prefs(this)
        p.enabled = !p.enabled
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs(this).enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (on) "Attivo" else "In pausa"
        }
        tile.updateTile()
    }

    companion object {
        /** Chiede al sistema di ridisegnare il tile (quando cambi stato dall'app). */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, BlockTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
