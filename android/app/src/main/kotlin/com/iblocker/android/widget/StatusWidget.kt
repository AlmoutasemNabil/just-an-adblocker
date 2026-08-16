package com.iblocker.android.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.iblocker.android.container
import com.iblocker.android.quickaction.QuickActions
import com.iblocker.android.tile.ProtectionTileService
import com.iblocker.android.vpn.IBlockerVpnService
import com.iblocker.android.vpn.VpnControl
import com.iblocker.core.log.BlockerStats
import com.iblocker.core.log.StatsPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Home-screen widget: blocked-today counter plus protection status; tapping
 * toggles protection. Same job as the iOS build's WidgetKit status widget.
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val blockedToday = StatsPersistence.load(context.container.paths.statsFile)
            .counters(BlockerStats.dayKey())
            .blocked
        val settings = context.container.settings.reload()
        val on = IBlockerVpnService.state.value.isOn || settings.protectionActive
        val paused = settings.activePauseUntil() != null

        provideContent {
            GlanceTheme {
                WidgetBody(blockedToday = blockedToday, on = on, paused = paused)
            }
        }
    }

    @Composable
    private fun WidgetBody(blockedToday: Long, on: Boolean, paused: Boolean) {
        val context = LocalContext.current
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionRunCallback<ToggleProtectionAction>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = NumberFormat.getInstance().format(blockedToday),
                style = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.primary,
                ),
            )
            Text(
                text = "blocked today",
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Text(
                text = when {
                    paused && on -> "Paused"
                    on -> "Protected"
                    else -> "Off — tap to protect"
                },
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface,
                ),
                modifier = GlanceModifier.padding(top = 6.dp),
            )
        }
    }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()
}

/**
 * Tapping the widget toggles protection in place. A widget interaction is one
 * of the cases where the system still lets an app start a foreground service
 * from the background; when the one-time VPN consent is missing there is
 * nothing to start, so the app opens instead.
 */
class ToggleProtectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (IBlockerVpnService.state.value.isOn) {
            VpnControl.stop(context)
        } else if (!VpnControl.start(context)) {
            runCatching { context.startActivity(QuickActions.intent(context, QuickActions.TOGGLE)) }
        }
        StatusWidget().updateAll(context)
    }
}

/** Redraws every surface that mirrors protection state: widgets and the tile. */
object WidgetRefresh {
    fun refreshAll(context: Context) {
        val applicationContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { StatusWidget().updateAll(applicationContext) }
        }
        runCatching {
            TileService.requestListeningState(
                applicationContext,
                ComponentName(applicationContext, ProtectionTileService::class.java),
            )
        }
    }
}
