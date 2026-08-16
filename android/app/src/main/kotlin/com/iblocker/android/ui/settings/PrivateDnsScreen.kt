package com.iblocker.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iblocker.android.ui.common.SectionCard
import com.iblocker.core.shared.DnsProviderPreset

/**
 * Blocking with the VPN off.
 *
 * iOS needs a generated `.mobileconfig` profile for this; Android has the
 * setting built in, so the app's job is to pick a provider, hand over the
 * hostname, and open the right Settings screen.
 */
@Composable
fun PrivateDnsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(DnsProviderPreset.adguard.id) }
    var nextDnsID by remember { mutableStateOf("") }

    val preset = if (selected == NEXTDNS) {
        nextDnsID.trim().takeIf { it.isNotEmpty() }?.let { DnsProviderPreset.nextDNS(it) }
    } else {
        DnsProviderPreset.all.firstOrNull { it.id == selected }
    }

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
            Text("Private DNS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionCard(
            title = "DNS provider",
            footer = "Pick a provider that blocks ads (AdGuard, Mullvad ad-blocking, NextDNS) if " +
                "Private DNS is your blocking mode while the tunnel is off.",
        ) {
            DnsProviderPreset.all.forEach { option ->
                ProviderRow(
                    name = option.name,
                    detail = option.detail,
                    selected = selected == option.id,
                    onSelect = { selected = option.id },
                )
            }
            ProviderRow(
                name = "NextDNS (your config)",
                detail = "Your own filtering rules and analytics",
                selected = selected == NEXTDNS,
                onSelect = { selected = NEXTDNS },
            )
            if (selected == NEXTDNS) {
                OutlinedTextField(
                    value = nextDnsID,
                    onValueChange = { nextDnsID = it },
                    label = { Text("NextDNS config ID (e.g. abc123)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        SectionCard(
            title = "How to install",
            footer = "Settings ▸ Network & internet ▸ Private DNS ▸ \"Private DNS provider hostname\", " +
                "then paste the hostname. It applies to every app, with no VPN slot used — and it " +
                "keeps working if you ever uninstall IBlocker.",
        ) {
            if (preset == null) {
                Text(
                    "Enter your NextDNS configuration ID to get a hostname.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preset.privateDnsHostname ?: "—",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        preset.privateDnsHostname?.let { copy(context, it) }
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy hostname")
                    }
                }
                Button(
                    onClick = { openPrivateDnsSettings(context) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Open Private DNS settings")
                }
            }
        }

        SectionCard(title = "Precedence") {
            Text(
                text = "active VPN tunnel DNS  >  Private DNS  >  the network's DHCP DNS\n\n" +
                    "While IBlocker's tunnel is connected its filtering wins. The moment it is off — " +
                    "you toggled it, or Android reclaimed the VPN slot — Private DNS takes over " +
                    "automatically. Running both is the belt-and-suspenders setup: there is never a " +
                    "moment with unfiltered DNS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val NEXTDNS = "nextdns"

@Composable
private fun ProviderRow(name: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Private DNS screen has a dedicated action on most builds; where it is
 * missing, the wireless/network settings screen is one tap away from it.
 */
private fun openPrivateDnsSettings(context: Context) {
    val candidates = listOf(
        Intent("android.settings.PRIVATE_DNS_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS hostname", text))
    Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}
