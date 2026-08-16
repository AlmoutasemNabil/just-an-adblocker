package com.iblocker.android.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iblocker.android.container
import com.iblocker.core.rules.DomainValidator
import kotlinx.coroutines.launch

/** Editor for the personal allow/deny domain lists. */
@Composable
fun AllowDenyScreen(allow: Boolean, modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val lists = context.container.lists
    val state by lists.state.collectAsState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    val domains = if (allow) state.userAllowlist else state.userDenylist

    fun add() {
        val candidate = input.trim()
        if (DomainValidator.normalize(candidate) == null) {
            invalid = true
            return
        }
        invalid = false
        input = ""
        scope.launch { if (allow) lists.addAllow(candidate) else lists.addDeny(candidate) }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (allow) "My allowlist" else "My blocklist",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = if (allow) {
                "These domains (and their subdomains) are never blocked, even if a filter list contains them."
            } else {
                "These domains (and their subdomains) are always blocked."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; invalid = false },
                label = { Text("example.com") },
                singleLine = true,
                isError = invalid,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = ::add, enabled = input.isNotBlank()) { Text("Add") }
        }
        if (invalid) {
            Text(
                "That doesn't look like a domain.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            items(domains, key = { it }) { domain ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = domain,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        scope.launch { if (allow) lists.removeAllow(domain) else lists.removeDeny(domain) }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove $domain")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
