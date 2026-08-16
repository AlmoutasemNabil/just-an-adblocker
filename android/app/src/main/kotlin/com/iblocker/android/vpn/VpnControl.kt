package com.iblocker.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.iblocker.android.container
import com.iblocker.android.widget.WidgetRefresh

/**
 * Process-independent protection control, used by the UI, the Quick Settings
 * tile, the widget, launcher shortcuts and automation apps. Every entry point
 * also mirrors state into the shared settings file and nudges the tile and
 * widgets to redraw.
 */
object VpnControl {

    /**
     * The one-time system consent dialog. Non-null means the caller must
     * launch this intent (from an Activity) before protection can start.
     */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun isConsentGranted(context: Context): Boolean = consentIntent(context) == null

    /** Starts protection. Returns false when the system consent dialog is still needed. */
    fun start(context: Context): Boolean {
        if (!isConsentGranted(context)) return false
        ContextCompat.startForegroundService(
            context,
            IBlockerVpnService.intent(context, IBlockerVpnService.ACTION_START),
        )
        return true
    }

    fun stop(context: Context) {
        context.startService(IBlockerVpnService.intent(context, IBlockerVpnService.ACTION_STOP))
        context.container.settings.update { it.copy(protectionActive = false) }
        WidgetRefresh.refreshAll(context)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        if (enabled) start(context) else { stop(context); true }

    fun toggle(context: Context): Boolean =
        setEnabled(context, !IBlockerVpnService.state.value.isOn)

    /**
     * Suspends blocking for [minutes]; it resumes on its own. The deadline is
     * written to shared settings first (so it survives even if the service is
     * mid-restart) and then pushed to the running service.
     */
    fun pause(context: Context, minutes: Int) {
        val until = System.currentTimeMillis() + minutes * 60_000L
        context.container.settings.update { it.copy(pausedUntilMillis = until) }
        syncPause(context)
    }

    fun resume(context: Context) {
        context.container.settings.update { it.copy(pausedUntilMillis = null) }
        syncPause(context)
    }

    private fun syncPause(context: Context) {
        if (IBlockerVpnService.state.value.isOn) {
            context.startService(IBlockerVpnService.intent(context, IBlockerVpnService.ACTION_SYNC_PAUSE))
        }
        WidgetRefresh.refreshAll(context)
    }

    /** Tells a running service to re-read the compiled blocklists right now. */
    fun reloadRules(context: Context) {
        if (IBlockerVpnService.state.value.isOn) {
            context.startService(IBlockerVpnService.intent(context, IBlockerVpnService.ACTION_RELOAD_RULES))
        }
    }

    /** Tells a running service to swap its upstream resolver. */
    fun applyUpstream(context: Context) {
        if (IBlockerVpnService.state.value.isOn) {
            context.startService(IBlockerVpnService.intent(context, IBlockerVpnService.ACTION_SET_UPSTREAM))
        }
    }

    /** Rebuilds the tun device so interface-level settings (app exclusions) take effect. */
    fun restart(context: Context) {
        if (!IBlockerVpnService.state.value.isOn) return
        context.startService(IBlockerVpnService.intent(context, IBlockerVpnService.ACTION_RESTART))
    }
}
