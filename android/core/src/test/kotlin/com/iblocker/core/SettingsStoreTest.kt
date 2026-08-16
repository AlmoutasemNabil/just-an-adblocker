package com.iblocker.core

import com.iblocker.core.json.Json
import com.iblocker.core.json.asLong
import com.iblocker.core.json.asObject
import com.iblocker.core.json.asString
import com.iblocker.core.shared.BypassStrategy
import com.iblocker.core.shared.IBlockerSettings
import com.iblocker.core.shared.SettingsStore
import com.iblocker.core.shared.UpstreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsStoreTest {

    private fun makeStore() = SettingsStore(File(makeTempDirectory(), "settings.json"))

    @Test
    fun defaultsAreTheSafeOnes() {
        val settings = makeStore().load()
        assertEquals(BypassStrategy.BLOCK_BYPASS_DOMAINS, settings.bypassStrategy)
        assertEquals(UpstreamConfig.DEFAULT, settings.upstream)
        assertTrue(settings.queryLogEnabled)
        assertTrue(settings.autoStartOnBoot)
        assertEquals(false, settings.onboardingComplete)
        assertNull(settings.pausedUntilMillis)
    }

    @Test
    fun roundTripsThroughDisk() {
        val store = makeStore()
        val custom = IBlockerSettings(
            upstream = UpstreamConfig(UpstreamConfig.Kind.UDP, udpAddress = "9.9.9.9"),
            onboardingComplete = true,
            queryLogEnabled = false,
            lastListUpdateMillis = 1_700_000_000_000L,
            bypassStrategy = BypassStrategy.ALLOW_ENCRYPTED_DNS,
            pausedUntilMillis = 1_800_000_000_000L,
            protectionActive = true,
            autoStartOnBoot = false,
            excludedPackages = listOf("com.example.one", "com.example.two"),
        )
        store.save(custom)
        assertEquals(custom, store.reload())
    }

    @Test
    fun updateIsReadModifyWrite() {
        val store = makeStore()
        store.update { it.copy(protectionActive = true) }
        store.update { it.copy(pausedUntilMillis = 42L) }
        val settings = store.reload()
        assertTrue(settings.protectionActive)
        assertEquals(42L, settings.pausedUntilMillis)
    }

    @Test
    fun activePauseIgnoresPastDeadlines() {
        val past = IBlockerSettings(pausedUntilMillis = 1_000L)
        assertNull(past.activePauseUntil(now = 2_000L))
        val future = IBlockerSettings(pausedUntilMillis = 5_000L)
        assertEquals(5_000L, future.activePauseUntil(now = 2_000L))
    }

    @Test
    fun garbageOnDiskFallsBackToDefaults() {
        val file = File(makeTempDirectory(), "settings.json")
        file.writeText("{not json at all")
        assertEquals(IBlockerSettings(), SettingsStore(file).load())
    }

    @Test
    fun upstreamConfigSurvivesJsonRoundTrip() {
        val config = UpstreamConfig(UpstreamConfig.Kind.DOH, dohURL = "https://94.140.14.14/dns-query")
        val decoded = UpstreamConfig.fromJson(Json.parse(Json.write(config.toJson())))
        assertEquals(config, decoded)
        assertEquals("https://94.140.14.14/dns-query", decoded.displayName)
    }

    @Test
    fun settingsFileIsHumanReadableJson() {
        val file = File(makeTempDirectory(), "settings.json")
        SettingsStore(file).save(IBlockerSettings(protectionActive = true, lastListUpdateMillis = 7L))

        val root = Json.parse(file.readText()).asObject()
        assertNotNull(root)
        assertEquals("blockBypassDomains", root!!["bypassStrategy"].asString())
        assertEquals(7L, root["lastListUpdateMillis"].asLong())
    }
}
