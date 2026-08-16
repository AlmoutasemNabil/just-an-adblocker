package com.iblocker.core.json

/**
 * A ~200-line JSON reader/writer, so `core` stays dependency-free and every
 * persisted document (stats, sources, settings) can be round-tripped in a
 * plain JVM unit test with no serialization plugin in the build.
 *
 * Values map to `Map<String, Any?>`, `List<Any?>`, `String`, `Long`/`Double`,
 * `Boolean` and `null`. Numbers without a fraction or exponent decode as
 * `Long`, so counters survive the trip byte-exactly.
 */
object Json {

    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd) throw ParseException("trailing content at ${parser.index}")
        return value
    }

    /** Returns null instead of throwing — the shape callers want when a file on disk may be garbage. */
    fun parseOrNull(text: String): Any? = try {
        parse(text)
    } catch (_: Exception) {
        null
    }

    fun write(value: Any?, pretty: Boolean = false): String {
        val out = StringBuilder()
        writeValue(value, out, pretty, 0)
        return out.toString()
    }

    // MARK: - Writing

    private fun writeValue(value: Any?, out: StringBuilder, pretty: Boolean, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is ULong -> out.append(value.toString())
            is UInt -> out.append(value.toString())
            is Double -> out.append(if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString())
            is Float -> writeValue(value.toDouble(), out, pretty, depth)
            is Map<*, *> -> writeObject(value, out, pretty, depth)
            is Iterable<*> -> writeArray(value, out, pretty, depth)
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeObject(map: Map<*, *>, out: StringBuilder, pretty: Boolean, depth: Int) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        out.append('{')
        var first = true
        // Sorted keys keep files diff-stable across writes.
        for (key in map.keys.map { it.toString() }.sorted()) {
            if (!first) out.append(',')
            first = false
            newline(out, pretty, depth + 1)
            writeString(key, out)
            out.append(':')
            if (pretty) out.append(' ')
            writeValue(map[key], out, pretty, depth + 1)
        }
        newline(out, pretty, depth)
        out.append('}')
    }

    private fun writeArray(list: Iterable<*>, out: StringBuilder, pretty: Boolean, depth: Int) {
        val items = list.toList()
        if (items.isEmpty()) {
            out.append("[]")
            return
        }
        out.append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            newline(out, pretty, depth + 1)
            writeValue(item, out, pretty, depth + 1)
        }
        newline(out, pretty, depth)
        out.append(']')
    }

    private fun newline(out: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    // MARK: - Parsing

    private class Parser(private val text: String) {
        var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd) throw ParseException("unexpected end of input")
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else throw ParseException("unexpected '$char' at $index")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd && text[index] == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (atEnd || text[index] != '"') throw ParseException("expected key at $index")
                val key = parseString()
                skipWhitespace()
                if (atEnd || text[index] != ':') throw ParseException("expected ':' at $index")
                index++
                result[key] = parseValue()
                skipWhitespace()
                if (atEnd) throw ParseException("unterminated object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw ParseException("expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd && text[index] == ']') {
                index++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                if (atEnd) throw ParseException("unterminated array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw ParseException("expected ',' or ']' at $index")
                }
            }
        }

        private fun parseString(): String {
            index++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd) throw ParseException("unterminated string")
                when (val char = text[index]) {
                    '"' -> {
                        index++
                        return out.toString()
                    }
                    '\\' -> {
                        index++
                        if (atEnd) throw ParseException("unterminated escape")
                        when (val escape = text[index]) {
                            '"', '\\', '/' -> out.append(escape)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (index + 4 >= text.length) throw ParseException("truncated \\u escape")
                                val hex = text.substring(index + 1, index + 5)
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw ParseException("bad escape '\\$escape' at $index")
                        }
                        index++
                    }
                    else -> {
                        out.append(char)
                        index++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (!atEnd && text[index] == '-') index++
            while (!atEnd && text[index].isDigit()) index++
            var isDecimal = false
            if (!atEnd && text[index] == '.') {
                isDecimal = true
                index++
                while (!atEnd && text[index].isDigit()) index++
            }
            if (!atEnd && (text[index] == 'e' || text[index] == 'E')) {
                isDecimal = true
                index++
                if (!atEnd && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd && text[index].isDigit()) index++
            }
            val literal = text.substring(start, index)
            if (literal.isEmpty() || literal == "-") throw ParseException("bad number at $start")
            return if (isDecimal) literal.toDouble() else (literal.toLongOrNull() ?: literal.toDouble())
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) throw ParseException("bad literal at $index")
            index += literal.length
            return value
        }
    }
}

// MARK: - Typed accessors

@Suppress("UNCHECKED_CAST")
fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asArray(): List<Any?>? = this as? List<Any?>

fun Any?.asString(): String? = this as? String

fun Any?.asBoolean(): Boolean? = this as? Boolean

fun Any?.asLong(): Long? = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is Double -> this.toLong()
    else -> null
}

fun Any?.asULong(): ULong? = asLong()?.toULong()

fun Any?.asUInt(): UInt? = asLong()?.toUInt()

fun Any?.asStringList(): List<String> = asArray()?.mapNotNull { it.asString() } ?: emptyList()
