package com.iblocker.android.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iblocker.android.container
import com.iblocker.core.dns.DnsRecordType
import com.iblocker.core.log.LogVerdict
import com.iblocker.core.log.QueryLogRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: String) { ALL("All"), BLOCKED("Blocked"), ALLOWED("Allowed") }

/**
 * Live DNS decisions with one-tap allow/block — the same 1 Hz tail of the
 * ring file the iOS app does, and the same long-press actions.
 */
@Composable
fun QueryLogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.container
    val store = container.queryLog
    val records by store.records.collectAsState()
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var search by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            store.poll()
            delay(1_000)
        }
    }

    val rows = remember(records, filter, search) {
        val query = search.trim().lowercase()
        records.asReversed()
            .filter { record ->
                when (filter) {
                    LogFilter.ALL -> true
                    LogFilter.BLOCKED -> record.verdict == LogVerdict.BLOCKED
                    LogFilter.ALLOWED -> record.verdict != LogVerdict.BLOCKED
                }
            }
            .filter { query.isEmpty() || it.domain.contains(query) }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Filter by domain") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach { entry ->
                FilterChip(
                    selected = filter == entry,
                    onClick = { filter = entry },
                    label = { Text(entry.label) },
                )
            }
        }

        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "DNS decisions appear here live while protection is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.timestampMillis.toString() + it.domain + it.qtype }) { record ->
                    LogRow(
                        record = record,
                        onAllow = { scope.launch { container.lists.addAllow(record.domain) } },
                        onDeny = { scope.launch { container.lists.addDeny(record.domain) } },
                        onCopy = { copyToClipboard(context, record.domain) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    record: QueryLogRecord,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val blocked = record.verdict == LogVerdict.BLOCKED
    val color = when (record.verdict) {
        LogVerdict.BLOCKED -> MaterialTheme.colorScheme.error
        LogVerdict.ALLOWED -> MaterialTheme.colorScheme.primary
        LogVerdict.FAILED -> MaterialTheme.colorScheme.tertiary
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.domain,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${DnsRecordType.name(record.qtype)} · ${formatTime(record.timestampMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = verdictLabel(record),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (blocked) {
                DropdownMenuItem(
                    text = { Text("Allow this domain") },
                    onClick = { menuOpen = false; onAllow() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Block this domain") },
                    onClick = { menuOpen = false; onDeny() },
                )
            }
            DropdownMenuItem(
                text = { Text("Copy domain") },
                onClick = { menuOpen = false; onCopy() },
            )
        }
    }
}

private fun verdictLabel(record: QueryLogRecord): String = when (record.verdict) {
    LogVerdict.BLOCKED -> "Blocked"
    LogVerdict.ALLOWED -> "Allowed"
    LogVerdict.FAILED -> "Failed"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("domain", text))
    Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}
