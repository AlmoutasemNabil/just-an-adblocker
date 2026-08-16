package com.iblocker.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.iblocker.android.ui.RootScreen
import com.iblocker.android.ui.theme.IBlockerTheme
import com.iblocker.android.vpn.VpnControl
import com.iblocker.android.work.ListRefreshWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** The system VPN consent dialog; starting protection is retried on approval. */
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) VpnControl.start(this)
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ListRefreshWorker.schedule(this)
        requestNotificationPermissionIfNeeded()

        val container = container
        container.scope.launch {
            container.lists.ensureFreshCompile()
            container.lists.refreshIfStale()
        }

        setContent {
            IBlockerTheme {
                RootScreen(onRequestProtection = ::requestProtection)
            }
        }
    }

    /**
     * Starts protection, showing the one-time system consent dialog first when
     * it has not been granted (or was revoked by another VPN taking the slot).
     */
    private fun requestProtection() {
        val consent: Intent? = VpnControl.consentIntent(this)
        if (consent != null) {
            consentLauncher.launch(consent)
        } else {
            VpnControl.start(this)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
