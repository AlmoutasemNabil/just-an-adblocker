package com.iblocker.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.iblocker.android.container
import com.iblocker.android.ui.dashboard.DashboardScreen
import com.iblocker.android.ui.lists.AllowDenyScreen
import com.iblocker.android.ui.lists.FilterListsScreen
import com.iblocker.android.ui.log.QueryLogScreen
import com.iblocker.android.ui.onboarding.OnboardingScreen
import com.iblocker.android.ui.settings.BlockingTestScreen
import com.iblocker.android.ui.settings.ExcludedAppsScreen
import com.iblocker.android.ui.settings.PrivateDnsScreen
import com.iblocker.android.ui.settings.SettingsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Shield),
    LOG("Log", Icons.AutoMirrored.Filled.ListAlt),
    LISTS("Lists", Icons.Filled.Checklist),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Pushed screens. A four-entry stack does not need a navigation library. */
enum class Detail {
    ALLOWLIST,
    DENYLIST,
    BLOCKING_TEST,
    PRIVATE_DNS,
    EXCLUDED_APPS,
}

@Composable
fun RootScreen(onRequestProtection: () -> Unit) {
    val context = LocalContext.current
    val container = context.container

    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var showOnboarding by remember { mutableStateOf(!container.settings.load().onboardingComplete) }

    if (showOnboarding) {
        OnboardingScreen(
            onRequestProtection = onRequestProtection,
            onFinished = {
                container.settings.update { it.copy(onboardingComplete = true) }
                showOnboarding = false
            },
        )
        return
    }

    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry && detail == null,
                        onClick = {
                            tab = entry
                            detail = null
                        },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        }
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (detail) {
            Detail.ALLOWLIST -> AllowDenyScreen(allow = true, modifier = contentModifier) { detail = null }
            Detail.DENYLIST -> AllowDenyScreen(allow = false, modifier = contentModifier) { detail = null }
            Detail.BLOCKING_TEST -> BlockingTestScreen(modifier = contentModifier) { detail = null }
            Detail.PRIVATE_DNS -> PrivateDnsScreen(modifier = contentModifier) { detail = null }
            Detail.EXCLUDED_APPS -> ExcludedAppsScreen(modifier = contentModifier) { detail = null }
            null -> when (tab) {
                Tab.DASHBOARD -> DashboardScreen(
                    modifier = contentModifier,
                    onRequestProtection = onRequestProtection,
                    onOpenBlockingTest = { detail = Detail.BLOCKING_TEST },
                )
                Tab.LOG -> QueryLogScreen(modifier = contentModifier)
                Tab.LISTS -> FilterListsScreen(
                    modifier = contentModifier,
                    onOpenAllowlist = { detail = Detail.ALLOWLIST },
                    onOpenDenylist = { detail = Detail.DENYLIST },
                )
                Tab.SETTINGS -> SettingsScreen(
                    modifier = contentModifier,
                    onOpenBlockingTest = { detail = Detail.BLOCKING_TEST },
                    onOpenPrivateDns = { detail = Detail.PRIVATE_DNS },
                    onOpenExcludedApps = { detail = Detail.EXCLUDED_APPS },
                )
            }
        }
    }
}
