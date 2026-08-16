package com.iblocker.core

import com.iblocker.core.json.Json
import com.iblocker.core.lists.BlocklistCompiler
import com.iblocker.core.lists.FetchResult
import com.iblocker.core.lists.FilterListSource
import com.iblocker.core.lists.FilterListState
import com.iblocker.core.lists.FilterListUpdater
import com.iblocker.core.rules.CompiledBlocklist
import com.iblocker.core.rules.FilterListParser
import com.iblocker.core.rules.Fnv1a
import com.iblocker.core.rules.SeedRules
import com.iblocker.core.rules.Verdict
import com.iblocker.core.shared.AppPaths
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance measure: in-app Google ads must be blocked — even on a fresh
 * install with zero downloaded lists.
 */
class SeedRulesTest {

    @Test
    fun seedTextParsesCleanly() {
        val parsed = FilterListParser.parse(SeedRules.text)
        assertEquals("typo in SeedRules.text", 0, parsed.skippedLines)
        assertTrue(parsed.allowDomains.isEmpty())
        assertEquals(17, parsed.blockDomains.size)
        assertTrue(parsed.blockDomains.contains("doubleclick.net"))
        assertTrue(parsed.blockDomains.contains("googlesyndication.com"))
        assertTrue(parsed.blockDomains.contains("app-measurement.com"))
    }

    @Test
    fun bundledSourceIsBuiltInAndEnabled() {
        val source = FilterListSource.builtIn.first()
        assertEquals(SeedRules.SOURCE_ID, source.id)
        assertTrue(source.enabled)
        assertTrue(source.isBuiltIn)

        // Fresh state carries it; older saved states gain it via the merge.
        assertTrue(FilterListState().sources.any { it.id == SeedRules.SOURCE_ID && it.enabled })
    }

    @Test
    fun compileWithZeroDownloadsBlocksGoogleInAppAds() = runTest {
        val paths = AppPaths(makeTempDirectory())
        paths.ensureDirectories()

        // Fresh install, every download failing: only the bundled core rules.
        val state = FilterListState()
        val updater = FilterListUpdater(paths) { FetchResult(statusCode = 503, body = ByteArray(0)) }
        val summary = updater.update(state)
        assertTrue(summary.updatedSourceIDs.isEmpty())
        // The bundled source is never fetched, so it cannot fail.
        assertNull(summary.failedSourceIDs[SeedRules.SOURCE_ID])

        val stats = BlocklistCompiler.compile(state, paths)
        assertTrue(stats.blockedEntryCount >= 17)
        assertEquals(17, state.metadata[SeedRules.SOURCE_ID]?.entryCount)

        val matcher = paths.loadMatcher()

        // The canonical AdMob request path — all must be blocked.
        assertEquals(Verdict.BLOCK, matcher.verdict("googleads.g.doubleclick.net"))
        assertEquals(Verdict.BLOCK, matcher.verdict("pagead2.googlesyndication.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("tpc.googlesyndication.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("app-measurement.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("x.app-measurement.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("adservice.google.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("admob.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("d.applovin.com"))

        // No overblocking of the surrounding legitimate domains.
        assertEquals(Verdict.NONE, matcher.verdict("google.com"))
        assertEquals(Verdict.NONE, matcher.verdict("www.google.com"))
        assertEquals(Verdict.NONE, matcher.verdict("apple.com"))
        assertEquals(Verdict.NONE, matcher.verdict("unity3d.com"))
    }

    @Test
    fun bypassTextParsesCleanly() {
        val parsed = FilterListParser.parse(SeedRules.bypassText)
        assertEquals("typo in SeedRules.bypassText", 0, parsed.skippedLines)
        assertEquals(10, parsed.blockDomains.size)
        assertTrue(parsed.blockDomains.contains("mozilla.cloudflare-dns.com"))
        assertTrue(parsed.blockDomains.contains("doh.opendns.com"))
    }

    @Test
    fun serviceFallbackBlocksWithNoBlobsOnDisk() {
        // The scenario that leaks in-app ads: the service running against a
        // missing/stale blob. The in-memory fallback must hold the floor.
        val paths = AppPaths(makeTempDirectory())
        val fallback = SeedRules.fallbackHashes(FilterListState())
        val matcher = paths.loadMatcher(builtInBlockHashes = fallback)

        assertEquals(Verdict.BLOCK, matcher.verdict("googleads.g.doubleclick.net"))
        assertEquals(Verdict.BLOCK, matcher.verdict("pagead2.googlesyndication.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("mozilla.cloudflare-dns.com"))
        assertEquals(Verdict.NONE, matcher.verdict("apple.com"))
        assertEquals(Verdict.NONE, matcher.verdict("cloudflare.com"))
        assertTrue(matcher.blockedEntryCount > 0)

        // Without the fallback, nothing matches — the old failure mode.
        assertEquals(Verdict.NONE, paths.loadMatcher().verdict("googleads.g.doubleclick.net"))
    }

    @Test
    fun fallbackRespectsDisabledBundledSources() {
        val state = FilterListState()
        val index = state.sources.indexOfFirst { it.id == SeedRules.BYPASS_SOURCE_ID }
        state.sources[index] = state.sources[index].copy(enabled = false)

        val fallback = SeedRules.fallbackHashes(state)
        assertFalse(fallback.contains(Fnv1a.hash64("mozilla.cloudflare-dns.com")))
        assertTrue(fallback.contains(Fnv1a.hash64("doubleclick.net")))
        assertFalse(SeedRules.isBypassBlockEnabled(state))

        // Sources missing from saved state (older builds) count as enabled.
        state.sources.removeAll { it.id == SeedRules.BYPASS_SOURCE_ID }
        assertTrue(SeedRules.fallbackHashes(state).contains(Fnv1a.hash64("mozilla.cloudflare-dns.com")))
        assertTrue(SeedRules.isBypassBlockEnabled(state))
    }

    @Test
    fun userAllowBeatsFallback() {
        val paths = AppPaths(makeTempDirectory())
        paths.ensureDirectories()
        CompiledBlocklist.write(listOf(Fnv1a.hash64("doubleclick.net")), 1u, paths.userAllowlistFile)
        val matcher = paths.loadMatcher(builtInBlockHashes = SeedRules.fallbackHashes(FilterListState()))
        assertEquals(Verdict.ALLOW, matcher.verdict("googleads.g.doubleclick.net"))
        assertEquals(Verdict.BLOCK, matcher.verdict("mozilla.cloudflare-dns.com"))
    }

    @Test
    fun compileStampsSeedVersion() {
        val paths = AppPaths(makeTempDirectory())
        paths.ensureDirectories()
        val state = FilterListState()
        assertNull(state.compiledSeedVersion)
        BlocklistCompiler.compile(state, paths)
        assertEquals(SeedRules.VERSION, state.compiledSeedVersion)

        // The compiled blob now carries the bypass rules too.
        assertEquals(Verdict.BLOCK, paths.loadMatcher().verdict("mozilla.cloudflare-dns.com"))
    }

    @Test
    fun legacyStateWithoutSeedVersionDecodes() {
        val legacyJson = """
            {"sources":[],"metadata":{},"userAllowlist":[],"userDenylist":[],"generation":9}
        """.trimIndent()
        val state = FilterListState.fromJson(Json.parse(legacyJson))
        assertNull(state.compiledSeedVersion)
        assertEquals(9u, state.generation)
    }

    @Test
    fun disablingBundledSourceRemovesSeedRules() {
        val paths = AppPaths(makeTempDirectory())
        paths.ensureDirectories()

        val state = FilterListState()
        val index = state.sources.indexOfFirst { it.id == SeedRules.SOURCE_ID }
        state.sources[index] = state.sources[index].copy(enabled = false)

        BlocklistCompiler.compile(state, paths)
        assertEquals(Verdict.NONE, paths.loadMatcher().verdict("googleads.g.doubleclick.net"))
    }
}
