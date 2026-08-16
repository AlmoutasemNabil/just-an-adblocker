package com.iblocker.android.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iblocker.android.container
import com.iblocker.android.ui.common.SectionCard
import com.iblocker.android.ui.common.formatCount
import com.iblocker.android.vpn.IBlockerVpnService
import com.iblocker.android.vpn.TunnelState
import com.iblocker.android.vpn.VpnControl
import com.iblocker.core.log.BlockerStats
import com.iblocker.core.log.StatsPersistence
import com.iblocker.core.rules.CompiledBlocklistView
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onRequestProtection: () -> Unit,
    onOpenBlockingTest: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val state by IBlockerVpnService.state.collectAsState()
    val runtimeStats by IBlockerVpnService.runtimeStats.collectAsState()

    var blockedToday by remember { mutableStateOf(0L) }
    var totalBlocked by remember { mutableStateOf(0L) }
    var ruleCount by remember { mutableStateOf(0L) }
    var pausedUntil by remember { mutableStateOf<Long?>(null) }

    // The same 3-second refresh the iOS dashboard runs: live counters from the
    // service when it is up, the persisted files when it is not.
    LaunchedEffect(state) {
        while (true) {
            val fileStats = StatsPersistence.load(container.paths.statsFile)
            blockedToday = fileStats.counters(BlockerStats.dayKey()).blocked
            totalBlocked = max(runtimeStats?.blockedQueries ?: 0L, fileStats.totalBlocked)
            ruleCount = runtimeStats?.blocklistEntryCount?.takeIf { it > 0 }
                ?: (CompiledBlocklistView.openOrNull(container.paths.blocklistFile)?.count?.toLong() ?: 0L)
            pausedUntil = runtimeStats?.pausedUntilMillis
                ?: container.settings.reload().activePauseUntil()
            delay(3_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Just an AdBlocker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp),
        )

        ProtectionToggle(
            state = state,
            onToggle = {
                if (state.isOn) VpnControl.stop(context) else onRequestProtection()
            },
        )

        StatusLine(state)

        PauseControls(
            enabled = state == TunnelState.CONNECTED,
            pausedUntil = pausedUntil,
            onPause = { minutes ->
                VpnControl.pause(context, minutes)
                pausedUntil = System.currentTimeMillis() + minutes * 60_000L
            },
            onResume = {
                VpnControl.resume(context)
                pausedUntil = null
            },
        )

        OutlinedButton(onClick = onOpenBlockingTest) {
            Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Verify ad blocking")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CounterTile("Blocked today", blockedToday, Modifier.weight(1f), MaterialTheme.colorScheme.error)
            CounterTile("Total blocked", totalBlocked, Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            CounterTile("Rules", ruleCount, Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
        }

        BlockedChart()

        Box(modifier = Modifier.height(24.dp))
    }
}

/** The hero on/off control: a big shield that starts and stops the tunnel. */
@Composable
private fun ProtectionToggle(state: TunnelState, onToggle: () -> Unit) {
    val on = state.isOn
    val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val scale by animateFloatAsState(if (on) 1f else 0.94f, label = "shield-scale")

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .alpha(scale)
                .background(tint.copy(alpha = 0.14f), CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = if (on) "Turn protection off" else "Turn protection on",
                    tint = tint,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    text = if (on) "ON" else "OFF",
                    color = tint,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                )
            }
        }
        if (state == TunnelState.CONNECTING || state == TunnelState.DISCONNECTING) {
            CircularProgressIndicator(modifier = Modifier.size(190.dp))
        }
    }
}

@Composable
private fun StatusLine(state: TunnelState) {
    val (text, color) = when (state) {
        TunnelState.CONNECTED -> "Protected — DNS filtering active" to MaterialTheme.colorScheme.primary
        TunnelState.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        TunnelState.DISCONNECTING -> "Stopping…" to MaterialTheme.colorScheme.onSurfaceVariant
        TunnelState.DISCONNECTED -> "Not protected" to MaterialTheme.colorScheme.onSurfaceVariant
        TunnelState.FAILED -> "Couldn't start — another VPN may hold the slot" to MaterialTheme.colorScheme.error
    }
    Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

@Composable
private fun PauseControls(
    enabled: Boolean,
    pausedUntil: Long?,
    onPause: (Int) -> Unit,
    onResume: () -> Unit,
) {
    if (!enabled) return
    val paused = pausedUntil != null && pausedUntil > System.currentTimeMillis()

    if (paused) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Paused — resumes in ${remainingMinutes(pausedUntil!!)} min",
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = onResume, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Resume now")
            }
        }
    } else {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.PauseCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Pause blocking")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(5, 15, 60).forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(if (minutes == 60) "Pause 1 hour" else "Pause $minutes minutes") },
                        onClick = {
                            expanded = false
                            onPause(minutes)
                        },
                    )
                }
            }
        }
    }
}

private fun remainingMinutes(until: Long): Long =
    max(1L, (until - System.currentTimeMillis()) / 60_000L)

@Composable
private fun CounterTile(title: String, value: Long, modifier: Modifier = Modifier, tint: Color) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = formatCount(value),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
    }
}

/** Blocked-per-hour bars for today plus the top blocked domains. */
@Composable
private fun BlockedChart() {
    val context = LocalContext.current
    val store = context.container.queryLog
    val records by store.records.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            store.poll()
            delay(1_000)
        }
    }

    val hourly = remember(records) { store.blockedPerHourToday() }
    val top = remember(records) { store.topBlockedDomains() }

    SectionCard(title = "Blocked today, by hour") {
        if (hourly.all { it == 0 }) {
            Text(
                "Nothing blocked yet. Turn protection on and browse — blocked queries land here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HourlyBars(hourly)
        }

        if (top.isNotEmpty()) {
            Text(
                text = "Top blocked",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            top.forEach { (domain, count) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(domain, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun HourlyBars(hourly: List<Int>) {
    val peak = max(1, hourly.max())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        hourly.forEachIndexed { hour, count ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((6 + 100 * count / peak).dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = if (count > 0) 0.85f else 0.15f),
                            MaterialTheme.shapes.extraSmall,
                        )
                )
                if (hour % 6 == 0) {
                    Text(
                        text = hour.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}
