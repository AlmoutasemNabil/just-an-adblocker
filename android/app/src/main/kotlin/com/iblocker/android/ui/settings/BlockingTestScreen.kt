package com.iblocker.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iblocker.android.container
import com.iblocker.android.probe.BlockingProbe
import com.iblocker.android.ui.common.SectionCard
import com.iblocker.android.vpn.IBlockerVpnService
import com.iblocker.android.vpn.TunnelState
import kotlinx.coroutines.launch
import java.text.NumberFormat

private data class ProbeItem(
    val host: String,
    val label: String,
    val expectBlocked: Boolean,
    val isBypassProbe: Boolean = false,
    /** True when the user chose to leave encrypted DNS alone: reports status, never fails the verdict. */
    val informational: Boolean = false,
    val outcome: BlockingProbe.Outcome? = null,
) {
    val passed: Boolean?
        get() {
            val outcome = outcome ?: return null
            if (informational) return true
            return when (outcome) {
                BlockingProbe.Outcome.Blocked, is BlockingProbe.Outcome.Unreachable -> expectBlocked
                is BlockingProbe.Outcome.Resolved -> !expectBlocked
            }
        }

    val isBypassReachable: Boolean
        get() = informational && outcome is BlockingProbe.Outcome.Resolved
}

/**
 * The acceptance test, in the app: resolves the canonical in-app ad domains
 * through the live system resolver and shows whether an ad SDK could reach
 * them right now.
 */
@Composable
fun BlockingTestScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val state by IBlockerVpnService.state.collectAsState()
    val runtimeStats by IBlockerVpnService.runtimeStats.collectAsState()

    val probes = remember {
        listOf(
            ProbeItem("googleads.g.doubleclick.net", "Google AdMob ad server", expectBlocked = true),
            ProbeItem("pagead2.googlesyndication.com", "Google ad delivery", expectBlocked = true),
            ProbeItem("app-measurement.com", "Google ad measurement", expectBlocked = true),
            ProbeItem("adservice.google.com", "Google ad service", expectBlocked = true),
            ProbeItem("mozilla.cloudflare-dns.com", "Browser DoH endpoint (bypass path)", expectBlocked = true, isBypassProbe = true),
            ProbeItem("doh.opendns.com", "Public DoH endpoint (bypass path)", expectBlocked = true, isBypassProbe = true),
            ProbeItem("android.com", "Control — must NOT be blocked", expectBlocked = false),
        ).toMutableStateList()
    }
    var isRunning by remember { mutableStateOf(false) }

    suspend fun run() {
        if (isRunning) return
        isRunning = true
        val bypassBlockEnabled = container.lists.isBypassBlockEnabled
        for (index in probes.indices) {
            probes[index] = probes[index].copy(
                outcome = null,
                informational = probes[index].isBypassProbe && !bypassBlockEnabled,
            )
        }
        for (index in probes.indices) {
            probes[index] = probes[index].copy(outcome = BlockingProbe.probe(probes[index].host))
        }
        isRunning = false
    }

    LaunchedEffect(Unit) { run() }

    val adProbes = probes.filter { !it.isBypassProbe }
    val verdict: Boolean? = if (adProbes.all { it.passed != null }) adProbes.all { it.passed == true } else null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Blocking test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionCard {
            when {
                state != TunnelState.CONNECTED -> Banner(
                    "Protection is off — turn it on first, then re-run.",
                    MaterialTheme.colorScheme.tertiary,
                )
                verdict == null -> Banner("Testing…", MaterialTheme.colorScheme.onSurfaceVariant)
                verdict -> Banner("In-app Google ads are BLOCKED", MaterialTheme.colorScheme.primary)
                else -> Banner(
                    "NOT fully blocked — check that protection is on and the lists are compiled.",
                    MaterialTheme.colorScheme.error,
                )
            }
            if (probes.any { it.isBypassReachable }) {
                Text(
                    text = "Encrypted-DNS endpoints are reachable (your choice). Apps with their own " +
                        "DoH resolver — some browsers — will keep loading their ads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SectionCard(title = "Probes") {
            probes.forEach { probe -> ProbeRow(probe) }
        }

        SectionCard(
            title = "Filter engine",
            footer = "Each probe resolves through the system DNS — the exact path every app's ad SDK " +
                "uses. \"Blocked\" means the filter answered with a blackhole address, so in-app ads " +
                "cannot load.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Active rules", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = NumberFormat.getInstance().format(runtimeStats?.blocklistEntryCount ?: 0L),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                enabled = !isRunning,
                onClick = {
                    scope.launch {
                        container.lists.compileOnly()
                        run()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Recompile rules and re-run")
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(text = text, color = color, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ProbeRow(probe: ProbeItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            probe.outcome == null -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
            probe.isBypassReachable -> Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            probe.passed == true -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            probe.isBypassProbe -> Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            else -> Icon(
                Icons.Filled.Cancel,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = probe.host,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = detailText(probe),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun detailText(probe: ProbeItem): String {
    val outcome = probe.outcome ?: return probe.label
    if (probe.isBypassReachable) return "${probe.label} — reachable, allowed by your bypass setting"
    return when (outcome) {
        BlockingProbe.Outcome.Blocked -> "${probe.label} — blocked (blackhole answer)"
        is BlockingProbe.Outcome.Unreachable -> "${probe.label} — unreachable (${outcome.reason})"
        is BlockingProbe.Outcome.Resolved ->
            "${probe.label} — resolves to ${outcome.addresses.firstOrNull() ?: "?"}"
    }
}
