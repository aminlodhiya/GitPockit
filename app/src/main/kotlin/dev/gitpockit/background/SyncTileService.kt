package dev.gitpockit.background

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.gitpockit.R
import dev.gitpockit.core.data.model.SyncTrigger
import javax.inject.Inject

/** Quick Settings tile: one tap syncs every repository. */
@AndroidEntryPoint
class SyncTileService : TileService() {
    @Inject lateinit var scheduler: SyncScheduler

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(R.string.tile_subtitle)
            updateTile()
        }
    }

    override fun onClick() {
        scheduler.request(null, SyncTrigger.TILE)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(R.string.notif_syncing)
            updateTile()
        }
    }
}
