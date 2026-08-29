package com.r1motor

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-Settings tile to toggle the software auto-rotate (replaces the broken system one). */
class RotateTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val p = getSharedPreferences("m", Context.MODE_PRIVATE)
        val on = !p.getBoolean("rotate", false)
        p.edit().putBoolean("rotate", on).apply()
        if (on) RotateService.start(this)
        else {
            RotateService.stop(this)
            Thread { runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system user_rotation 0")).waitFor() } }.start()
        }
        refresh(on)
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh(getSharedPreferences("m", Context.MODE_PRIVATE).getBoolean("rotate", false))
    }

    private fun refresh(on: Boolean) {
        qsTile?.apply {
            label = "Auto-rotate"
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
