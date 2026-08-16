package com.iblocker.android.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.iblocker.android.container
import com.iblocker.android.vpn.VpnControl
import com.iblocker.android.work.ListRefreshWorker

/**
 * Brings protection back after a reboot or an app update — the Android
 * counterpart of the iOS build's on-demand VPN rules.
 *
 * Only fires when protection was on when the device went down and the user
 * left "start on boot" enabled. Consent survives reboots, so no dialog is
 * needed; if it was revoked, starting is a no-op and the UI shows the shield
 * as off. (For a guarantee that no query escapes before the app starts, the
 * system's own "Always-on VPN" switch is the stronger option — Settings ▸
 * Network ▸ VPN ▸ IBlocker.)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        ListRefreshWorker.schedule(context)

        val settings = context.container.settings.reload()
        if (!settings.autoStartOnBoot || !settings.protectionActive) return

        if (!VpnControl.start(context)) {
            Log.w("IBlockerBoot", "VPN consent missing after boot — protection stays off until the app is opened")
        }
    }
}
