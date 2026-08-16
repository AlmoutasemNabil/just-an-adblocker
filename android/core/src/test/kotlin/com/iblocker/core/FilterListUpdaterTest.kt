package com.iblocker.core

import com.iblocker.core.lists.BlocklistCompiler
import com.iblocker.core.lists.FetchResult
import com.iblocker.core.lists.FilterListSource
import com.iblocker.core.lists.FilterListState
import com.iblocker.core.lists.FilterListUpdater
import com.iblocker.core.shared.AppPaths
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class FilterListUpdaterTest {

    private fun makeState(sourceID: String = "test") = FilterListState().apply {
        sources = mutableListOf(
            FilterListSource(sourceID, "Test", "https://lists.example.com/test.txt", enabled = true, isBuiltIn = false),
        )
    }

    @Test
    fun downloadCacheAndConditionalGet() = runTest {
        val paths = AppPaths(makeTempDirectory())
        val state = makeState()

        val listBody = "0.0.0.0 ads.example.com\n||tracker.net^\n".toByteArray()
        val updater = FilterListUpdater(paths) { request ->
            if (request.headers["If-None-Match"] == "v1") {
                FetchResult(statusCode = 304, body = ByteArray(0))
            } else {
                FetchResult(
                    statusCode = 200,
                    body = listBody,
                    etag = "v1",
                    lastModified = "Mon, 01 Jan 2026 00:00:00 GMT",
                )
            }
        }

        val first = updater.update(state)
        assertEquals(listOf("test"), first.updatedSourceIDs)
        assertTrue(first.failedSourceIDs.isEmpty())
        assertEquals("v1", state.metadata["test"]?.etag)
        assertArrayEquals(listBody, paths.cachedListFile("test").readBytes())

        val second = updater.update(state)
        assertEquals(listOf("test"), second.unchangedSourceIDs)
        assertTrue(second.updatedSourceIDs.isEmpty())

        // force ignores the cache validators
        val third = updater.update(state, force = true)
        assertEquals(listOf("test"), third.updatedSourceIDs)

        // Compile from the cache.
        val stats = BlocklistCompiler.compile(state, paths)
        assertEquals(2, stats.blockedEntryCount)
        assertEquals(2, state.metadata["test"]?.entryCount)
    }

    @Test
    fun failureIsRecordedAndDoesNotThrow() = runTest {
        val paths = AppPaths(makeTempDirectory())
        val state = makeState()

        val summary = FilterListUpdater(paths) { FetchResult(statusCode = 503, body = ByteArray(0)) }.update(state)
        assertTrue(summary.updatedSourceIDs.isEmpty())
        assertNotNull(summary.failedSourceIDs["test"])
        assertNotNull(state.metadata["test"]?.lastError)
    }

    @Test
    fun networkExceptionsBecomeReadableErrors() = runTest {
        val paths = AppPaths(makeTempDirectory())
        val state = makeState()

        val summary = FilterListUpdater(paths) { throw java.net.UnknownHostException("lists.example.com") }
            .update(state)
        assertEquals("Can't reach the server", summary.failedSourceIDs["test"])
    }

    @Test
    fun disabledSourcesAreNotFetched() = runTest {
        val paths = AppPaths(makeTempDirectory())
        val state = makeState()
        state.sources[0] = state.sources[0].copy(enabled = false)

        val summary = FilterListUpdater(paths) {
            fail("disabled source must not be fetched")
            FetchResult(statusCode = 200, body = ByteArray(0))
        }.update(state)
        assertTrue(summary.updatedSourceIDs.isEmpty())
    }

    @Test
    fun statePersistenceRoundTripAndBuiltInMerge() {
        val file = File(makeTempDirectory(), "sources.json")
        val state = FilterListState()
        state.userAllowlist = mutableListOf("keep.example.com")
        state.generation = 9u
        state.save(file)

        val loaded = FilterListState.load(file)
        assertEquals(listOf("keep.example.com"), loaded.userAllowlist)
        assertEquals(9u, loaded.generation)
        assertTrue(loaded.sources.map { it.id }.containsAll(FilterListSource.builtIn.map { it.id }))

        // Fresh load with no file yields the built-ins.
        val fresh = FilterListState.load(File(file.path + ".missing"))
        assertEquals(FilterListSource.builtIn.map { it.id }, fresh.sources.map { it.id })
        assertTrue(fresh.sources.first { it.id == "oisd-big" }.enabled)
    }
}
