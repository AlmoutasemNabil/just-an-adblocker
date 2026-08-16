package com.iblocker.android

import com.iblocker.android.upstream.DohUpstream
import com.iblocker.android.upstream.UdpUpstream
import com.iblocker.android.upstream.UpstreamFactory
import com.iblocker.core.shared.UpstreamConfig
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of the resolver wiring that is pure JVM: which upstream a
 * stored configuration selects, and which configurations are rejected outright
 * rather than failing every query at runtime.
 */
class UpstreamFactoryTest {

    @Test
    fun defaultConfigBuildsDoh() {
        assertTrue(UpstreamFactory.make(UpstreamConfig.DEFAULT) is DohUpstream)
    }

    @Test
    fun udpConfigBuildsUdpUpstream() {
        val config = UpstreamConfig(UpstreamConfig.Kind.UDP, udpAddress = "9.9.9.9")
        assertTrue(UpstreamFactory.make(config) is UdpUpstream)
    }

    @Test
    fun incompleteConfigsAreRejected() {
        assertNull(UpstreamFactory.make(UpstreamConfig(UpstreamConfig.Kind.DOH, dohURL = null)))
        assertNull(UpstreamFactory.make(UpstreamConfig(UpstreamConfig.Kind.UDP, udpAddress = "")))
        assertNull(UpstreamFactory.make(UpstreamConfig(UpstreamConfig.Kind.UDP, udpAddress = null)))
    }

    @Test
    fun plaintextDohEndpointsAreRejected() {
        // A DoH upstream that is not HTTPS would defeat the point of using one.
        assertNull(UpstreamFactory.make(UpstreamConfig(UpstreamConfig.Kind.DOH, dohURL = "http://1.1.1.1/dns-query")))
    }
}
