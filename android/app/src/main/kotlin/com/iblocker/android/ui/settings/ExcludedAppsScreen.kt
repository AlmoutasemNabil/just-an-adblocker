package com.iblocker.android.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iblocker.android.container
import com.iblocker.android.vpn.VpnControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(val packageName: String, val label: String)

/**
 * Per-app exclusions — something iOS cannot offer at all (a packet tunnel
 * there is never told which app sent a packet). Excluded apps leave the
 * tunnel entirely, so their DNS is unfiltered.
 */
@Composable
fun ExcludedAppsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container

    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var excluded by remember { mutableStateOf(container.settings.reload().excludedPackages.toSet()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Excluded apps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "Checked apps bypass the tunnel completely — their DNS is never filtered, and their " +
                "ads are never blocked. Changes restart protection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val list = apps
        if (list == null) {
            CircularProgressIndicator()
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = excluded.contains(app.packageName),
                        onCheckedChange = { checked ->
                            excluded = if (checked) excluded + app.packageName else excluded - app.packageName
                            container.settings.update { it.copy(excludedPackages = excluded.sorted()) }
                            VpnControl.restart(context)
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    return runCatching {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { info ->
                // Only apps the user can actually launch: system plumbing has
                // no ads to block and no business in this list.
                packageManager.getLaunchIntentForPackage(info.packageName) != null ||
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
