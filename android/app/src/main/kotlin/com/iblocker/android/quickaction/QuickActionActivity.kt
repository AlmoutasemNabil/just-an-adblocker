package com.iblocker.android.quickaction

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.iblocker.android.MainActivity
import com.iblocker.android.R
import com.iblocker.android.container
import com.iblocker.android.data.FilterListsRepository
import com.iblocker.android.vpn.VpnControl
import kotlinx.coroutines.launch

/**
 * Every "do one thing without opening the app" entry point: launcher
 * shortcuts, the widget's buttons, the ongoing notification's actions, and
 * any automation app that can fire an intent (Tasker, Automate, adb).
 *
 * This is the Android counterpart of the iOS build's App Intents and Siri
 * phrases — "turn on protection", "pause 15 minutes", "update lists" — minus
 * the voice layer, which on Android belongs to the assistant, not the app.
 */
class QuickActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent?.action)
        finish()
    }

    private fun handle(action: String?) {
        when (action) {
            QuickActions.ENABLE -> start()
            QuickActions.DISABLE -> VpnControl.stop(this)
            QuickActions.TOGGLE -> if (com.iblocker.android.vpn.IBlockerVpnService.state.value.isOn) {
                VpnControl.stop(this)
            } else {
                start()
            }
            QuickActions.RESUME -> VpnControl.resume(this)
            QuickActions.PAUSE_5 -> pause(5)
            QuickActions.PAUSE_15 -> pause(15)
            QuickActions.PAUSE_60 -> pause(60)
            QuickActions.PAUSE -> pause(intent?.getIntExtra(QuickActions.EXTRA_MINUTES, 5) ?: 5)
            QuickActions.UPDATE_LISTS -> updateLists()
            else -> openApp()
        }
    }

    private fun start() {
        if (VpnControl.start(this)) return
        // First run, or consent revoked: the system dialog needs an activity.
        openApp()
    }

    private fun pause(minutes: Int) {
        VpnControl.pause(this, minutes)
        toast(getString(R.string.toast_paused, minutes))
    }

    private fun updateLists() {
        val container = container
        toast(getString(R.string.toast_updating_lists))
        container.scope.launch {
            val result = container.lists.updateAndCompile(force = false)
            if (result is FilterListsRepository.UpdateOutcome.Done) {
                VpnControl.reloadRules(applicationContext)
            }
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

object QuickActions {
    const val ENABLE = "com.iblocker.android.action.ENABLE"
    const val DISABLE = "com.iblocker.android.action.DISABLE"
    const val TOGGLE = "com.iblocker.android.action.TOGGLE"
    const val PAUSE = "com.iblocker.android.action.PAUSE"
    const val RESUME = "com.iblocker.android.action.RESUME"
    const val UPDATE_LISTS = "com.iblocker.android.action.UPDATE_LISTS"

    /** Fixed-duration variants, so notification/widget buttons need no extras. */
    const val PAUSE_5 = "com.iblocker.android.action.PAUSE_5"
    const val PAUSE_15 = "com.iblocker.android.action.PAUSE_15"
    const val PAUSE_60 = "com.iblocker.android.action.PAUSE_60"

    const val EXTRA_MINUTES = "minutes"

    fun intent(context: Context, action: String): Intent =
        Intent(context, QuickActionActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    fun pending(context: Context, action: String): PendingIntent = PendingIntent.getActivity(
        context,
        action.hashCode(),
        intent(context, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
