package com.iblocker.android.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iblocker.android.BuildConfig
import com.iblocker.android.container
import com.iblocker.android.ui.common.SectionCard
import com.iblocker.android.vpn.VpnControl
import com.iblocker.core.shared.BypassStrategy
import com.iblocker.core.shared.DnsProviderPreset
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenBlockingTest: () -> Unit,
    onOpenPrivateDns: () -> Unit,
    onOpenExcludedApps: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val listState by container.lists.state.collectAsState()

    var settings by remember { mutableStateOf(container.settings.reload()) }

    fun update(transform: (com.iblocker.core.shared.IBlockerSettings) -> com.iblocker.core.shared.IBlockerSettings) {
        settings = container.settings.update(transform)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard(
            footer = "Resolves the Google in-app ad domains through the live system resolver and " +
                "shows whether an ad SDK could reach them.",
        ) {
            TextButton(onClick = onOpenBlockingTest, modifier = Modifier.fillMaxWidth()) {
                Text("Verify blocking (in-app ad test)", modifier = Modifier.weight(1f))
            }
        }

        // The Android counterpart of the iOS build's Apple-relay strategy:
        // same problem (traffic that never asks the system resolver), a
        // different bypass path.
        SectionCard(
            title = "Encrypted-DNS bypass",
            footer = bypassFooter(settings.bypassStrategy),
        ) {
            BypassStrategy.entries.forEach { strategy ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            update { it.copy(bypassStrategy = strategy) }
                            scope.launch {
                                container.lists.setBypassBlock(strategy == BypassStrategy.BLOCK_BYPASS_DOMAINS)
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.bypassStrategy == strategy, onClick = null)
                    Text(
                        text = when (strategy) {
                            BypassStrategy.BLOCK_BYPASS_DOMAINS -> "Block DoH/DoT endpoints"
                            BypassStrategy.ALLOW_ENCRYPTED_DNS -> "Leave encrypted DNS alone"
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        SectionCard(
            title = "Upstream DNS",
            footer = "Where non-blocked queries are resolved. Use IP-literal endpoints only — " +
                "hostnames can't be resolved from inside the tunnel.",
        ) {
            DnsProviderPreset.tunnelUpstreams.forEach { (name, config) ->
                UpstreamRow(
                    name = name,
                    selected = settings.upstream == config,
                    onSelect = {
                        update { it.copy(upstream = config) }
                        VpnControl.applyUpstream(context)
                    },
                )
            }
        }

        SectionCard(
            title = "Blocking without the VPN",
            footer = "Android's own Private DNS points every app at a filtering resolver, with no VPN " +
                "slot used. While IBlocker's tunnel is on, the tunnel's DNS wins; Private DNS takes " +
                "over whenever it is off.",
        ) {
            TextButton(onClick = onOpenPrivateDns, modifier = Modifier.fillMaxWidth()) {
                Text("Set up Private DNS", modifier = Modifier.weight(1f))
            }
        }

        SectionCard(
            title = "Apps",
            footer = "Excluded apps leave the tunnel entirely — their DNS is never filtered. " +
                "Useful for a banking app that dislikes VPNs, or a work profile app.",
        ) {
            TextButton(onClick = onOpenExcludedApps, modifier = Modifier.fillMaxWidth()) {
                Text("Excluded apps", modifier = Modifier.weight(1f))
                Text(
                    text = settings.excludedPackages.size.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = "Startup",
            footer = "For a guarantee that nothing escapes before the app starts, turn on the system's " +
                "own Always-on VPN for IBlocker in Settings ▸ Network ▸ VPN.",
        ) {
            ToggleRow(
                label = "Start protection after reboot",
                checked = settings.autoStartOnBoot,
                onChange = { update { s -> s.copy(autoStartOnBoot = it) } },
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open system VPN settings", modifier = Modifier.weight(1f))
            }
        }

        SectionCard(
            title = "Privacy",
            footer = "The log never leaves this device. Turning it off takes effect the next time " +
                "protection starts.",
        ) {
            ToggleRow(
                label = "Keep a query log",
                checked = settings.queryLogEnabled,
                onChange = { update { s -> s.copy(queryLogEnabled = it) } },
            )
        }

        SectionCard(title = "About") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Version", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Active lists", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = listState.sources.count { it.enabled }.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "IBlocker runs entirely on-device: a local VPN inspects only DNS lookups and " +
                    "answers blocked ones itself. No accounts, no subscriptions, no telemetry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun UpstreamRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun bypassFooter(strategy: BypassStrategy): String = when (strategy) {
    BypassStrategy.BLOCK_BYPASS_DOMAINS ->
        "A DNS filter only sees lookups that reach the system resolver. Apps that speak DoH/DoT " +
            "straight to a hardcoded endpoint (some browsers) never ask, so their ads load. " +
            "Blocking those endpoints pushes them back onto system DNS, where this filter sees them. " +
            "Trade-off: if you set one of those hostnames as your system Private DNS, it stops " +
            "working while protection is on."

    BypassStrategy.ALLOW_ENCRYPTED_DNS ->
        "Nothing encrypted-DNS related is blocked. Apps with their own resolver keep it — and keep " +
            "their ads. Choose this if you rely on a Private DNS hostname while the tunnel runs."
}
