package com.iblocker.core.log

enum class LogVerdict(val raw: Int) {
    ALLOWED(0),
    BLOCKED(1),
    FAILED(2),
    ;

    companion object {
        fun from(raw: Int): LogVerdict? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One DNS decision, encoded as a fixed 64-byte record:
 *
 *     0   timestampMillis  u64 LE
 *     8   qtype            u16 LE
 *     10  verdict          u8
 *     11  listId           u8   (reserved, 0 in v1)
 *     12  domainLen        u8
 *     13  flags            u8   (reserved)
 *     14  reserved         u16
 *     16  domain UTF-8, up to 48 bytes, zero padded
 */
data class QueryLogRecord(
    val timestampMillis: Long,
    val qtype: Int,
    val verdict: LogVerdict,
    val listId: Int = 0,
    val domain: String,
) {
    fun encoded(): ByteArray {
        val out = ByteArray(RECORD_SIZE)
        for (i in 0 until 8) {
            out[i] = ((timestampMillis ushr (i * 8)) and 0xFF).toByte()
        }
        out[8] = (qtype and 0xFF).toByte()
        out[9] = ((qtype shr 8) and 0xFF).toByte()
        out[10] = verdict.raw.toByte()
        out[11] = listId.toByte()

        var domainBytes = domain.toByteArray(Charsets.UTF_8)
        if (domainBytes.size > MAX_DOMAIN_BYTES) {
            domainBytes = domainBytes.copyOfRange(domainBytes.size - MAX_DOMAIN_BYTES, domainBytes.size)
        }
        out[12] = domainBytes.size.toByte()
        domainBytes.copyInto(out, 16)
        return out
    }

    companion object {
        const val RECORD_SIZE = 64
        const val MAX_DOMAIN_BYTES = 48

        /** Decodes one 64-byte slot; returns null for torn/blank slots. */
        fun decode(bytes: ByteArray, offset: Int = 0): QueryLogRecord? {
            if (offset + RECORD_SIZE > bytes.size) return null

            var ts = 0L
            for (i in 7 downTo 0) {
                ts = (ts shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            if (ts == 0L) return null

            val qtype = (bytes[offset + 8].toInt() and 0xFF) or ((bytes[offset + 9].toInt() and 0xFF) shl 8)
            val verdict = LogVerdict.from(bytes[offset + 10].toInt() and 0xFF) ?: return null

            val domainLength = bytes[offset + 12].toInt() and 0xFF
            if (domainLength <= 0 || domainLength > MAX_DOMAIN_BYTES) return null
            val domainBytes = bytes.copyOfRange(offset + 16, offset + 16 + domainLength)
            if (domainBytes.any { (it.toInt() and 0xFF) < 0x21 || (it.toInt() and 0xFF) > 0x7E }) return null

            return QueryLogRecord(
                timestampMillis = ts,
                qtype = qtype,
                verdict = verdict,
                listId = bytes[offset + 11].toInt() and 0xFF,
                domain = String(domainBytes, Charsets.UTF_8),
            )
        }
    }
}
