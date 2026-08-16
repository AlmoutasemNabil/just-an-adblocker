package com.iblocker.android.data

import android.content.Context
import com.iblocker.android.net.OkHttpFetcher
import com.iblocker.android.vpn.VpnControl
import com.iblocker.core.lists.BlocklistCompiler
import com.iblocker.core.lists.CompileStats
import com.iblocker.core.lists.FilterListMetadata
import com.iblocker.core.lists.FilterListSource
import com.iblocker.core.lists.FilterListState
import com.iblocker.core.lists.FilterListUpdater
import com.iblocker.core.lists.UpdateSummary
import com.iblocker.core.rules.DomainValidator
import com.iblocker.core.rules.SeedRules
import com.iblocker.core.shared.AppPaths
import com.iblocker.core.shared.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The Lists feature's state and every operation on it, shared by the UI, the
 * background refresh worker and the "update lists" shortcut. Mirrors the iOS
 * build's FilterListsViewModel, minus the view.
 *
 * Every mutation persists sources.json, recompiles the mmap'd blobs off the
 * main thread, and tells a running service to reload.
 */
class FilterListsRepository(
    private val context: Context,
    private val paths: AppPaths,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    sealed interface UpdateOutcome {
        data class Done(val summary: UpdateSummary?, val stats: CompileStats?) : UpdateOutcome
        data object Busy : UpdateOutcome
    }

    private val _state = MutableStateFlow(FilterListState.load(paths.filterStateFile))
    val state: StateFlow<FilterListState> = _state.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _lastCompileStats = MutableStateFlow<CompileStats?>(null)
    val lastCompileStats: StateFlow<CompileStats?> = _lastCompileStats.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val mutex = Mutex()

    fun metadata(sourceID: String): FilterListMetadata =
        _state.value.metadata[sourceID] ?: FilterListMetadata()

    fun clearError() {
        _errorMessage.value = null
    }

    // MARK: - Sources

    suspend fun setSource(id: String, enabled: Boolean) {
        val state = _state.value.copyState()
        val index = state.sources.indexOfFirst { it.id == id }
        if (index < 0) return
        state.sources[index] = state.sources[index].copy(enabled = enabled)
        publish(state)
        if (SeedRules.bundledText(id) != null) {
            // Bundled sources need no download — recompile is enough.
            compileOnly()
        } else {
            updateAndCompile(force = false)
        }
    }

    /**
     * The "Block encrypted-DNS bypass" bundled source, surfaced in Settings as
     * the DNS-bypass strategy.
     */
    val isBypassBlockEnabled: Boolean
        get() = SeedRules.isBypassBlockEnabled(_state.value)

    suspend fun setBypassBlock(enabled: Boolean) = setSource(SeedRules.BYPASS_SOURCE_ID, enabled)

    /** Validates by test-downloading before accepting the source. */
    suspend fun addCustomSource(name: String, url: String): Boolean {
        val id = "custom-" + UUID.randomUUID().toString().take(8).lowercase()
        val probe = FilterListState().apply {
            sources = mutableListOf(FilterListSource(id, name, url, enabled = true, isBuiltIn = false))
        }
        val summary = withContext(Dispatchers.IO) {
            FilterListUpdater(paths, fetcher = OkHttpFetcher).update(probe, force = true)
        }
        if (!summary.updatedSourceIDs.contains(id)) {
            _errorMessage.value = summary.failedSourceIDs[id] ?: "Download failed"
            return false
        }

        val state = _state.value.copyState()
        state.sources.add(FilterListSource(id, name, url, enabled = true, isBuiltIn = false))
        probe.metadata[id]?.let { state.metadata[id] = it }
        publish(state)
        compileOnly()
        return true
    }

    suspend fun removeSource(id: String) {
        val state = _state.value.copyState()
        val index = state.sources.indexOfFirst { it.id == id }
        if (index < 0 || state.sources[index].isBuiltIn) return
        state.sources.removeAt(index)
        state.metadata.remove(id)
        runCatching { paths.cachedListFile(id).delete() }
        publish(state)
        compileOnly()
    }

    // MARK: - Allow / deny

    suspend fun addAllow(domain: String) = addPersonal(domain, allow = true)

    suspend fun addDeny(domain: String) = addPersonal(domain, allow = false)

    private suspend fun addPersonal(domain: String, allow: Boolean) {
        val normalized = DomainValidator.normalize(domain) ?: return
        val state = _state.value.copyState()
        val list = if (allow) state.userAllowlist else state.userDenylist
        if (list.contains(normalized)) return
        list.add(normalized)
        publish(state)
        compileOnly()
    }

    suspend fun removeAllow(domain: String) {
        val state = _state.value.copyState()
        state.userAllowlist.remove(domain)
        publish(state)
        compileOnly()
    }

    suspend fun removeDeny(domain: String) {
        val state = _state.value.copyState()
        state.userDenylist.remove(domain)
        publish(state)
        compileOnly()
    }

    // MARK: - Update & compile

    suspend fun updateAndCompile(force: Boolean): UpdateOutcome {
        if (_isUpdating.value) return UpdateOutcome.Busy
        _isUpdating.value = true
        try {
            val working = _state.value.copyState()
            val summary = withContext(Dispatchers.IO) {
                FilterListUpdater(paths, fetcher = OkHttpFetcher).update(working, force)
            }
            publish(working)

            _errorMessage.value = if (summary.failedSourceIDs.isEmpty()) {
                null
            } else {
                "Update failed for: " + summary.failedSourceIDs.keys.sorted().joinToString(", ")
            }

            val stats = compileOnly()
            settings.update { it.copy(lastListUpdateMillis = System.currentTimeMillis()) }
            return UpdateOutcome.Done(summary, stats)
        } finally {
            _isUpdating.value = false
        }
    }

    /** Recompiles from the cached list files off the main thread (big lists take hundreds of ms). */
    suspend fun compileOnly(): CompileStats? = mutex.withLock {
        val working = _state.value.copyState()
        val stats = withContext(Dispatchers.Default) {
            runCatching { BlocklistCompiler.compile(working, paths) }.getOrNull()
        } ?: return@withLock null

        _lastCompileStats.value = stats
        publish(working)
        VpnControl.reloadRules(context)
        stats
    }

    /**
     * Recompiles when the app's built-in rules changed since the on-device
     * blob was produced (app updates), or the blob is missing. Cheap no-op
     * otherwise. Closes the gap where an updated app ships new seed rules but
     * the old blob keeps serving the filter.
     */
    suspend fun ensureFreshCompile() {
        if (!paths.blocklistFile.isFile || _state.value.compiledSeedVersion != SeedRules.VERSION) {
            compileOnly()
        }
    }

    /** Foreground staleness check: the periodic worker is best-effort. */
    suspend fun refreshIfStale() {
        val last = settings.reload().lastListUpdateMillis ?: 0L
        if (System.currentTimeMillis() - last > 24 * 3600 * 1000L) {
            updateAndCompile(force = false)
        }
    }

    private fun publish(state: FilterListState) {
        runCatching { state.save(paths.filterStateFile) }
        _state.value = state
    }
}
