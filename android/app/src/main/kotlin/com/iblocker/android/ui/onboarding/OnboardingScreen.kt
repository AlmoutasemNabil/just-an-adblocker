package com.iblocker.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iblocker.android.container
import com.iblocker.android.vpn.IBlockerVpnService
import com.iblocker.android.vpn.TunnelState
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable
fun OnboardingScreen(onRequestProtection: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val state by IBlockerVpnService.state.collectAsState()
    val isUpdating by container.lists.isUpdating.collectAsState()
    val compileStats by container.lists.lastCompileStats.collectAsState()
    val error by container.lists.errorMessage.collectAsState()

    var step by remember { mutableStateOf(0) }
    var downloadDone by remember { mutableStateOf(false) }

    when (step) {
        0 -> Page(
            icon = Icons.Filled.Shield,
            title = "Block ads everywhere",
            text = "IBlocker runs a tiny VPN that never leaves your device. It looks at one thing only — " +
                "DNS lookups — and answers the ones that belong to ad and tracking networks with " +
                "\"nothing here\".\n\nEvery app benefits, not just your browser. No subscription, no " +
                "account, no traffic sent anywhere.",
        ) {
            Button(onClick = { step = 1 }) { Text("Continue") }
        }

        1 -> Page(
            icon = Icons.Filled.CloudDownload,
            title = "Get the blocklist",
            text = "IBlocker uses OISD — a well-maintained list that blocks ads and trackers without " +
                "breaking apps or sites. You can add more lists later.",
        ) {
            when {
                isUpdating -> CircularProgressIndicator()
                downloadDone -> {
                    Text(
                        text = "${NumberFormat.getInstance().format(compileStats?.blockedEntryCount ?: 0)} rules ready",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Button(onClick = { step = 2 }) { Text("Continue") }
                }
                else -> {
                    Button(onClick = {
                        scope.launch {
                            container.lists.updateAndCompile(force = true)
                            downloadDone = (container.lists.lastCompileStats.value?.blockedEntryCount ?: 0) > 0
                        }
                    }) { Text("Download blocklist") }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { step = 2 }) { Text("Skip — use built-in rules") }
                }
            }
        }

        else -> Page(
            icon = Icons.Filled.PowerSettingsNew,
            title = "Turn it on",
            text = "Android will ask permission to set up a VPN connection — that's the on-device " +
                "filter. Nothing is routed through third-party servers; only DNS lookups are " +
                "inspected, locally.",
        ) {
            if (state == TunnelState.CONNECTED) {
                Text(
                    "Protection is on",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onFinished) { Text("Done") }
            } else {
                Button(onClick = onRequestProtection) { Text("Enable protection") }
                TextButton(onClick = onFinished) { Text("Skip for now") }
            }
        }
    }
}

@Composable
private fun Page(
    icon: ImageVector,
    title: String,
    text: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        )
        Box(modifier = Modifier.size(28.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions()
        }
    }
}
