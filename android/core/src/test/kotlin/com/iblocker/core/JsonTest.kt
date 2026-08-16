package com.iblocker.core

import com.iblocker.core.json.Json
import com.iblocker.core.json.asArray
import com.iblocker.core.json.asBoolean
import com.iblocker.core.json.asLong
import com.iblocker.core.json.asObject
import com.iblocker.core.json.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-rolled reader/writer that keeps `core` dependency-free. */
class JsonTest {

    @Test
    fun roundTripsNestedDocuments() {
        val document = mapOf(
            "name" to "sources",
            "count" to 3L,
            "enabled" to true,
            "missing" to null,
            "ratio" to 0.25,
            "items" to listOf(
                mapOf("id" to "a", "n" to 1L),
                mapOf("id" to "b", "n" to 2L),
            ),
        )
        val decoded = Json.parse(Json.write(document, pretty = true)).asObject()!!

        assertEquals("sources", decoded["name"].asString())
        assertEquals(3L, decoded["count"].asLong())
        assertEquals(true, decoded["enabled"].asBoolean())
        assertNull(decoded["missing"])
        assertEquals(0.25, decoded["ratio"] as Double, 0.0)
        assertEquals(2, decoded["items"].asArray()!!.size)
        assertEquals("b", decoded["items"].asArray()!![1].asObject()!!["id"].asString())
    }

    @Test
    fun integersDoNotBecomeFloats() {
        // Counters must survive byte-exactly; 1e15 as a Double would not.
        val encoded = Json.write(mapOf("total" to 1_700_000_000_123L))
        assertTrue(encoded.contains("1700000000123"))
        assertEquals(1_700_000_000_123L, Json.parse(encoded).asObject()!!["total"].asLong())
    }

    @Test
    fun escapesAndUnescapesStrings() {
        val awkward = "quote\" backslash\\ newline\n tab\t unicodeé"
        val decoded = Json.parse(Json.write(mapOf("s" to awkward))).asObject()!!["s"].asString()
        assertEquals(awkward, decoded)
    }

    @Test
    fun keysAreSortedForStableFiles() {
        assertEquals("""{"a":1,"b":2,"c":3}""", Json.write(mapOf("c" to 3L, "a" to 1L, "b" to 2L)))
    }

    @Test
    fun malformedInputReturnsNullRatherThanCrashing() {
        assertNull(Json.parseOrNull("{"))
        assertNull(Json.parseOrNull("""{"a":}"""))
        assertNull(Json.parseOrNull("""{"a":1} trailing"""))
        assertNull(Json.parseOrNull(""))
    }

    @Test
    fun emptyContainersRoundTrip() {
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}").asObject())
        assertEquals(emptyList<Any?>(), Json.parse("[]").asArray())
        assertEquals("{}", Json.write(emptyMap<String, Any?>(), pretty = true))
    }
}
