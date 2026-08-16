package com.iblocker.android

import android.app.Application
import android.content.Context
import com.iblocker.android.data.FilterListsRepository
import com.iblocker.android.data.QueryLogStore
import com.iblocker.core.shared.AppPaths
import com.iblocker.core.shared.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Process-wide wiring. The VPN service, the UI, the tile, the widget and the
 * background worker all run in this one process, so they share these
 * instances instead of talking over IPC the way the iOS app and its Network
 * Extension have to.
 */
class IBlockerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        container(this)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /** Safe from any component, including ones the system starts before onCreate finishes. */
        fun container(context: Context): AppContainer {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return AppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}

class AppContainer(private val context: Context) {

    val paths: AppPaths = AppPaths(context.filesDir).also { it.ensureDirectories() }

    val settings: SettingsStore = SettingsStore(paths.settingsFile)

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val lists: FilterListsRepository by lazy { FilterListsRepository(context, paths, settings, scope) }

    val queryLog: QueryLogStore by lazy { QueryLogStore(paths) }
}

val Context.container: AppContainer
    get() = IBlockerApplication.container(this)
