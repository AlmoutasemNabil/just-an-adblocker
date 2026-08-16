package com.iblocker.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iblocker.android.container
import com.iblocker.android.data.FilterListsRepository
import com.iblocker.android.vpn.VpnControl
import com.iblocker.android.widget.WidgetRefresh
import java.util.concurrent.TimeUnit

/**
 * Daily blocklist refresh. WorkManager's periodic work is the counterpart of
 * the iOS build's BGAppRefresh task — best-effort by design, which is why the
 * app also refreshes stale lists when it comes to the foreground.
 */
class ListRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        return when (container.lists.updateAndCompile(force = false)) {
            is FilterListsRepository.UpdateOutcome.Done -> {
                VpnControl.reloadRules(applicationContext)
                WidgetRefresh.refreshAll(applicationContext)
                Result.success()
            }
            FilterListsRepository.UpdateOutcome.Busy -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "iblocker-list-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ListRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
