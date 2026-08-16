package com.iblocker.core.shared

import com.iblocker.core.lists.FilterListState
import com.iblocker.core.rules.CompiledBlocklistView
import com.iblocker.core.rules.DomainMatcher
import com.iblocker.core.rules.SeedRules
import java.io.File

/**
 * Canonical locations of every file the UI and the VPN service share.
 *
 * On iOS these live in an App Group container because the tunnel is a
 * separate process with its own sandbox; on Android both halves are the same
 * app, so the app's private files directory is the container. The file names
 * and formats are identical across platforms.
 */
class AppPaths(val containerDir: File) {

    val blocklistFile: File get() = File(containerDir, "blocklist.bin")
    val userAllowlistFile: File get() = File(containerDir, "allowlist.bin")
    val userDenylistFile: File get() = File(containerDir, "denylist.bin")
    val queryLogFile: File get() = File(containerDir, "querylog.ring")
    val statsFile: File get() = File(containerDir, "stats.json")
    val filterStateFile: File get() = File(containerDir, "sources.json")
    val settingsFile: File get() = File(containerDir, "settings.json")
    val listsCacheDir: File get() = File(containerDir, "lists")

    fun cachedListFile(sourceID: String): File = File(listsCacheDir, "$sourceID.txt")

    fun ensureDirectories() {
        containerDir.mkdirs()
        listsCacheDir.mkdirs()
    }

    /**
     * Loads the current matcher from disk; missing files yield an empty
     * matcher rather than an error so the service can start before the first
     * list download completes. Pass [builtInBlockHashes] (the seed fallback)
     * so blocking never depends solely on the on-disk blob.
     */
    fun loadMatcher(builtInBlockHashes: Set<ULong> = emptySet()): DomainMatcher = DomainMatcher(
        blocklist = CompiledBlocklistView.openOrNull(blocklistFile),
        userAllowlist = CompiledBlocklistView.openOrNull(userAllowlistFile),
        userDenylist = CompiledBlocklistView.openOrNull(userDenylistFile),
        builtInBlockHashes = builtInBlockHashes,
    )

    /**
     * The matcher used by the running service: the on-disk blobs PLUS the
     * in-memory seed fallback for every bundled source the user has left
     * enabled — so the guaranteed floor holds even when the compiled blob is
     * missing or stale.
     */
    fun currentMatcher(): DomainMatcher {
        val state = FilterListState.load(filterStateFile)
        return loadMatcher(builtInBlockHashes = SeedRules.fallbackHashes(state))
    }
}
