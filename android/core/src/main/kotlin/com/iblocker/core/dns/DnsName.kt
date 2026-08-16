package com.iblocker.core.dns

enum class DnsWireError {
    TRUNCATED,
    BAD_LABEL,
    POINTER_LOOP,
    NAME_TOO_LONG,
    NOT_A_QUERY,
    UNSUPPORTED_QUESTION_COUNT,
}

class DnsWireException(val error: DnsWireError) : Exception(error.name)

/** Encoder/decoder for DNS wire-format names (RFC 1035 section 3.1). */
object DnsNameCodec {

    data class Decoded(val name: String, val wireLength: Int)

    /**
     * Decodes a name starting at [offset].
     *
     * Returns the lowercased dotted name (no trailing dot) and the number of
     * bytes the name occupies at [offset] (up to and including the zero label,
     * or the 2-byte compression pointer if one is encountered first).
     * Compression pointers are followed, but only backwards, so malicious
     * loops terminate.
     */
    @Throws(DnsWireException::class)
    fun decode(bytes: ByteArray, offset: Int): Decoded {
        val labels = ArrayList<String>()
        var i = offset
        var wireLength = -1
        var nameBytes = 0

        while (true) {
            if (i < 0 || i >= bytes.size) throw DnsWireException(DnsWireError.TRUNCATED)
            val len = bytes[i].toInt() and 0xFF

            if (len == 0) {
                if (wireLength < 0) wireLength = i - offset + 1
                break
            }

            if (len and 0xC0 == 0xC0) {
                if (i + 1 >= bytes.size) throw DnsWireException(DnsWireError.TRUNCATED)
                if (wireLength < 0) wireLength = i - offset + 2
                val target = ((len and 0x3F) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                if (target >= i) throw DnsWireException(DnsWireError.POINTER_LOOP)
                i = target
                continue
            }

            if (len and 0xC0 != 0) throw DnsWireException(DnsWireError.BAD_LABEL)
            if (i + 1 + len > bytes.size) throw DnsWireException(DnsWireError.TRUNCATED)
            nameBytes += len + 1
            if (nameBytes > 255) throw DnsWireException(DnsWireError.NAME_TOO_LONG)

            val label = StringBuilder(len)
            for (j in (i + 1)..(i + len)) {
                var c = bytes[j].toInt() and 0xFF
                if (c in 0x41..0x5A) c += 0x20
                label.append(c.toChar())
            }
            labels.add(label.toString())
            i += len + 1
        }

        return Decoded(labels.joinToString("."), wireLength)
    }

    /**
     * Encodes a dotted name into wire format (no compression).
     * Returns null if the name is not encodable.
     */
    fun encode(name: String): ByteArray? {
        val out = ArrayList<Byte>()
        for (label in name.split(".")) {
            val utf8 = label.toByteArray(Charsets.UTF_8)
            if (utf8.isEmpty() || utf8.size > 63) return null
            out.add(utf8.size.toByte())
            utf8.forEach { out.add(it) }
        }
        out.add(0)
        if (out.size > 255) return null
        return out.toByteArray()
    }
}
