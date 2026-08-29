package com.r1motor

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-Settings tile: tap cycles Rear -> Front -> Stow. */
class MotorTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val next = when (pos) { Motor.REAR -> Motor.FRONT; Motor.FRONT -> Motor.STOW; else -> Motor.REAR }
        Thread { Motor.set(next) }.start()
        pos = next; refresh()
    }

    override fun onStartListening() { super.onStartListening(); refresh() }

    private fun refresh() {
        qsTile?.apply {
            label = "Camera: " + when (pos) { Motor.FRONT -> "Front"; Motor.REAR -> "Rear"; else -> "Stow" }
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    companion object { @Volatile var pos = Motor.STOW }
}
