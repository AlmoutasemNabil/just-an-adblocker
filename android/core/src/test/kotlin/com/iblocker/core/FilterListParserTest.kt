package com.iblocker.core

import com.iblocker.core.rules.DomainValidator
import com.iblocker.core.rules.FilterListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterListParserTest {

    @Test
    fun parsesMixedFormats() {
        val text = """
            [Adblock Plus 2.0]
            ! Title: Sample list
            # hosts-style comment

            0.0.0.0 ads.example.com
            0.0.0.0 tracker.one.net tracker.two.net
            127.0.0.1 localhost
            :: metrics.example.org
            0.0.0.0 localhost.localdomain broadcasthost

            plain-domain.example.io
            inline.example.com # with a trailing note

            ||adservice.example.net^
            ||important.example.net^${'$'}important
            ||modified.example.net^${'$'}third-party
            @@||allowed.example.com^
            @@||alsoallowed.example.com^${'$'}badfilter

            example.com##.ad-banner
            /banner[0-9]+/
            ||wild*.example.com^
            not_a_domain
        """.trimIndent()

        val parsed = FilterListParser.parse(text)

        assertEquals(
            setOf(
                "ads.example.com",
                "tracker.one.net", "tracker.two.net",
                "metrics.example.org",
                "plain-domain.example.io",
                "inline.example.com",
                "adservice.example.net",
                "important.example.net",
            ),
            parsed.blockDomains.toSet(),
        )
        assertEquals(
            setOf("allowed.example.com", "alsoallowed.example.com"),
            parsed.allowDomains.toSet(),
        )

        // ||modified…${'$'}third-party, cosmetic, regex, wildcard, not_a_domain
        assertEquals(5, parsed.skippedLines)
    }

    @Test
    fun crlfListsParseCleanly() {
        val parsed = FilterListParser.parse("0.0.0.0 ads.example.com\r\n||tracker.net^\r\n")
        assertEquals(listOf("ads.example.com", "tracker.net"), parsed.blockDomains)
        assertEquals(0, parsed.skippedLines)
    }

    @Test
    fun localhostFamilyIsIgnoredSilently() {
        val parsed = FilterListParser.parse(
            """
            127.0.0.1 localhost
            ::1 ip6-localhost ip6-loopback
            """.trimIndent()
        )
        assertTrue(parsed.blockDomains.isEmpty())
        assertEquals(0, parsed.skippedLines)
    }

    @Test
    fun hostsLineWithOnlyInvalidNamesCountsSkipped() {
        val parsed = FilterListParser.parse("0.0.0.0 %%bogus%%")
        assertTrue(parsed.blockDomains.isEmpty())
        assertEquals(1, parsed.skippedLines)
    }

    @Test
    fun nonAsciiLinesAreSkipped() {
        val parsed = FilterListParser.parse("münchen-ads.example.de")
        assertTrue(parsed.blockDomains.isEmpty())
        assertEquals(1, parsed.skippedLines)
    }

    @Test
    fun domainValidator() {
        assertEquals("ads.example.com", DomainValidator.normalize("ADS.Example.COM."))
        assertEquals("spaced.example.com", DomainValidator.normalize("  spaced.example.com "))
        assertEquals("under_score.example.com", DomainValidator.normalize("under_score.example.com"))
        assertNull(DomainValidator.normalize("nodots"))
        assertNull(DomainValidator.normalize(".leading.example.com"))
        assertNull(DomainValidator.normalize("double..dot.com"))
        assertNull(DomainValidator.normalize("bad domain.com"))
        assertNull(DomainValidator.normalize("emoji🎉.example.com"))
        assertNull(DomainValidator.normalize("a".repeat(64) + ".example.com"))
        assertNull(DomainValidator.normalize((0 until 40).joinToString(".") { "x".repeat(10) }))
    }
}
