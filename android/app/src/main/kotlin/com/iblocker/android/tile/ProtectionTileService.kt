package com.iblocker.android.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.iblocker.android.MainActivity
import com.iblocker.android.R
import com.iblocker.android.vpn.IBlockerVpnService
import com.iblocker.android.vpn.VpnControl

/**
 * Quick Settings toggle — the counterpart of the iOS build's Control Center
 * control. Flips protection without opening the app; falls back to opening it
 * when the one-time system VPN consent has not been given yet.
 */
class ProtectionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (IBlockerVpnService.state.value.isOn) {
            VpnControl.stop(this)
        } else if (!VpnControl.start(this)) {
            openApp()
            return
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = IBlockerVpnService.state.value.isOn
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_shield)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
