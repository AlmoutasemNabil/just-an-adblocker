package com.iblocker.core

import com.iblocker.core.lists.BlocklistCompiler
import com.iblocker.core.lists.FilterListSource
import com.iblocker.core.lists.FilterListState
import com.iblocker.core.rules.CompiledBlocklist
import com.iblocker.core.rules.CompiledBlocklistView
import com.iblocker.core.rules.DomainMatcher
import com.iblocker.core.rules.Fnv1a
import com.iblocker.core.rules.Verdict
import com.iblocker.core.shared.AppPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompilerAndMatcherTest {

    private fun makePaths(): AppPaths = AppPaths(makeTempDirectory())

    @Test
    fun compileAndMatchEndToEnd() {
        val paths = makePaths()
        paths.ensureDirectories()

        val state = FilterListState()
        state.sources = mutableListOf(
            FilterListSource("test-a", "A", "https://example.com/a", enabled = true, isBuiltIn = false),
            FilterListSource("test-b", "B", "https://example.com/b", enabled = true, isBuiltIn = false),
            FilterListSource("test-off", "Off", "https://example.com/c", enabled = false, isBuiltIn = false),
        )
        state.userAllowlist = mutableListOf("safe.doubleclick.net")
        state.userDenylist = mutableListOf("mytracker.example.com")

        paths.cachedListFile("test-a").writeText(
            """
            0.0.0.0 ads.example.com
            ||doubleclick.net^
            @@||okay.example.com^
            okay.example.com
            """.trimIndent()
        )
        paths.cachedListFile("test-b").writeText("tracker.io")
        paths.cachedListFile("test-off").writeText("disabled.example.com")

        val stats = BlocklistCompiler.compile(state, paths)
        assertEquals(1u, stats.generation)
        // ads.example.com, doubleclick.net, tracker.io (okay.example.com subtracted by @@)
        assertEquals(3, stats.blockedEntryCount)
        assertEquals(1, stats.userAllowEntryCount)
        assertEquals(1, stats.userDenyEntryCount)

        val matcher = paths.loadMatcher()
        assertEquals(3, matcher.blockedEntryCount)

        // Exact and subdomain blocking.
        assertEquals(Verdict.BLOCK, matcher.verdict("ads.example.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("sub.ads.example.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("doubleclick.net"))
        assertEquals(Verdict.BLOCK, matcher.verdict("x.y.doubleclick.net"))
        assertEquals(Verdict.BLOCK, matcher.verdict("tracker.io"))

        // List-level @@ subtraction.
        assertEquals(Verdict.NONE, matcher.verdict("okay.example.com"))

        // Disabled source not compiled.
        assertEquals(Verdict.NONE, matcher.verdict("disabled.example.com"))

        // User allow beats the blocklist, including for subdomains.
        assertEquals(Verdict.ALLOW, matcher.verdict("safe.doubleclick.net"))
        assertEquals(Verdict.ALLOW, matcher.verdict("deep.safe.doubleclick.net"))

        // User deny blocks unlisted domains.
        assertEquals(Verdict.BLOCK, matcher.verdict("mytracker.example.com"))
        assertEquals(Verdict.BLOCK, matcher.verdict("cdn.mytracker.example.com"))

        // Unrelated domains and TLD-only names never match.
        assertEquals(Verdict.NONE, matcher.verdict("www.apple.com"))
        assertEquals(Verdict.NONE, matcher.verdict("com"))
        assertEquals(Verdict.NONE, matcher.verdict("example.com"))

        // Second compile bumps the generation.
        val second = BlocklistCompiler.compile(state, paths)
        assertEquals(2u, second.generation)
        assertEquals(2u, CompiledBlocklistView.open(paths.blocklistFile).generation)
    }

    @Test
    fun emptyBlocklistMatchesNothing() {
        val paths = makePaths()
        CompiledBlocklist.write(emptyList(), 1u, paths.blocklistFile)
        val view = CompiledBlocklistView.open(paths.blocklistFile)
        assertTrue(view.isEmpty)
        assertEquals(Verdict.NONE, DomainMatcher(blocklist = view).verdict("anything.example.com"))
    }

    @Test
    fun corruptBlobIsRejected() {
        val paths = makePaths()
        paths.ensureDirectories()
        paths.blocklistFile.writeText("garbage")
        assertNull(CompiledBlocklistView.openOrNull(paths.blocklistFile))

        val valid = CompiledBlocklist.serialize(listOf(1uL, 2uL, 3uL), 1u)
        "XXXX".toByteArray().copyInto(valid, 0)
        paths.blocklistFile.writeBytes(valid)
        assertNull(CompiledBlocklistView.openOrNull(paths.blocklistFile))
    }

    @Test
    fun propertyFiftyThousandDomains() {
        val paths = makePaths()
        paths.ensureDirectories()
        val generator = SeededGenerator(0xB10C4E12uL)

        val blocked = HashSet<String>()
        val notBlocked = HashSet<String>()
        while (blocked.size < 25_000) {
            blocked.add("h${generator.next() % 1_000_000_000uL}.blocked-${generator.next() % 10_000uL}.example")
        }
        while (notBlocked.size < 25_000) {
            notBlocked.add("h${generator.next() % 1_000_000_000uL}.clean-${generator.next() % 10_000uL}.example")
        }
        notBlocked.removeAll(blocked)

        CompiledBlocklist.write(blocked.map { Fnv1a.hash64(it) }, 7u, paths.blocklistFile)
        val matcher = DomainMatcher(blocklist = CompiledBlocklistView.open(paths.blocklistFile))

        for (domain in blocked) {
            assertEquals("false negative for $domain", Verdict.BLOCK, matcher.verdict(domain))
            assertEquals("subdomain miss for $domain", Verdict.BLOCK, matcher.verdict("www.$domain"))
        }
        // 64-bit hashes: collisions across 50k domains are effectively impossible.
        assertEquals(0, notBlocked.count { matcher.verdict(it) == Verdict.BLOCK })
    }

    @Test
    fun serializeIsSortedAndDeduplicated() {
        val view = writeAndOpen(CompiledBlocklist.serialize(listOf(9uL, 3uL, 3uL, 7uL, 1uL), 5u))
        assertEquals(4, view.count)
        assertEquals(5u, view.generation)
        assertTrue(view.contains(1uL))
        assertTrue(view.contains(3uL))
        assertTrue(view.contains(7uL))
        assertTrue(view.contains(9uL))
        assertFalse(view.contains(2uL))
        assertFalse(view.contains(0uL))
        assertFalse(view.contains(ULong.MAX_VALUE))
    }

    @Test
    fun binarySearchHandlesHashesAboveSignedRange() {
        // FNV-1a fills all 64 bits; a signed comparison would mis-order these.
        val hashes = listOf(1uL, 0x7FFF_FFFF_FFFF_FFFFuL, 0x8000_0000_0000_0000uL, ULong.MAX_VALUE - 1uL)
        val view = writeAndOpen(CompiledBlocklist.serialize(hashes, 1u))
        for (hash in hashes) assertTrue("missing $hash", view.contains(hash))
        assertFalse(view.contains(ULong.MAX_VALUE))
        assertFalse(view.contains(0x8000_0000_0000_0001uL))
    }

    private fun writeAndOpen(bytes: ByteArray): CompiledBlocklistView {
        val file = File(makeTempDirectory(), "blob.bin")
        file.writeBytes(bytes)
        return CompiledBlocklistView.open(file)
    }
}
