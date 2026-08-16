package com.iblocker.android.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iblocker.android.container
import com.iblocker.android.ui.common.SectionCard
import com.iblocker.core.lists.FilterListSource
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable
fun FilterListsScreen(
    modifier: Modifier = Modifier,
    onOpenAllowlist: () -> Unit,
    onOpenDenylist: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val lists = container.lists
    val state by lists.state.collectAsState()
    val isUpdating by lists.isUpdating.collectAsState()
    val compileStats by lists.lastCompileStats.collectAsState()
    val error by lists.errorMessage.collectAsState()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Filter Lists",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add list")
                    }
                    if (isUpdating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
                    } else {
                        IconButton(onClick = { scope.launch { lists.updateAndCompile(force = true) } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Update now")
                        }
                    }
                }
            }
        }

        compileStats?.let { stats ->
            item {
                SectionCard(
                    footer = "Every rule blocks a domain and all of its subdomains, in every app.",
                ) {
                    LabeledRow("Active rules", NumberFormat.getInstance().format(stats.blockedEntryCount))
                    if (stats.skippedLines > 0) {
                        LabeledRow("Skipped lines", NumberFormat.getInstance().format(stats.skippedLines))
                    }
                }
            }
        }

        item {
            Text(
                "Blocklists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        items(state.sources, key = { it.id }) { source ->
            SourceRow(
                source = source,
                entryCount = lists.metadata(source.id).entryCount,
                lastError = lists.metadata(source.id).lastError,
                onToggle = { enabled -> scope.launch { lists.setSource(source.id, enabled) } },
                onRemove = { scope.launch { lists.removeSource(source.id) } },
            )
        }

        item {
            SectionCard(
                title = "Personal rules",
                footer = "Your allowlist always wins — use it to unbreak a site.",
            ) {
                LinkRow("My allowlist", state.userAllowlist.size, onOpenAllowlist)
                LinkRow("My blocklist", state.userDenylist.size, onOpenDenylist)
            }
        }

        error?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { Column(modifier = Modifier.padding(bottom = 24.dp)) {} }
    }

    if (showAddDialog) {
        AddListDialog(
            onDismiss = {
                showAddDialog = false
                lists.clearError()
            },
            onAdd = { name, url ->
                scope.launch {
                    if (lists.addCustomSource(name, url)) showAddDialog = false
                }
            },
            error = error,
        )
    }
}

@Composable
private fun SourceRow(
    source: FilterListSource,
    entryCount: Int,
    lastError: String?,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.bodyLarge)
                if (source.enabled && entryCount > 0) {
                    Text(
                        text = "${NumberFormat.getInstance().format(entryCount)} rules",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lastError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!source.isBuiltIn) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove list")
                }
            }
            Switch(checked = source.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkRow(label: String, badge: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(badge.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddListDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit, error: String?) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add blocklist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://…") },
                    singleLine = true,
                )
                Text(
                    "Any hosts-format, domain-list or AdGuard-style DNS list URL. " +
                        "The list is test-downloaded before it's added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = url.startsWith("https://"),
                onClick = {
                    val displayName = name.ifBlank { url.removePrefix("https://").substringBefore('/') }
                    onAdd(displayName, url.trim())
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
