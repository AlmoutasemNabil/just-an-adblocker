package com.iblocker.core.log

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Fixed-size ring file for query-log records, written by the VPN service and
 * tailed by the UI. Layout:
 *
 *     0     "IBLG" magic
 *     4     version u32 LE (=1)
 *     8     recordSize u32 LE (=64)
 *     12    capacity u32 LE
 *     16    writeCursor u64 LE  (monotonic record index, never wraps)
 *     4096  capacity x 64-byte records, slot = cursor % capacity
 *
 * Bounded disk by construction, O(1) append, and torn writes damage at most
 * one 64-byte slot, which the reader's validation discards. No locking:
 * the cursor is published after the records land; readers double-check
 * record contents.
 */
object QueryLogRing {
    val magic = "IBLG".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val HEADER_SIZE = 4096L
    const val DEFAULT_CAPACITY = 65536
}

class QueryLogRingWriter(
    file: File,
    private val capacity: Int = QueryLogRing.DEFAULT_CAPACITY,
    private val flushThreshold: Int = 128,
) : Closeable {

    private val handle: RandomAccessFile
    private var cursor: Long
    private val pending = ArrayList<QueryLogRecord>()

    init {
        file.parentFile?.mkdirs()
        var needsInit = !file.isFile
        if (!needsInit) {
            // Re-validate an existing file; recreate on mismatch.
            RandomAccessFile(file, "r").use { existing ->
                val header = ByteArray(24)
                val read = existing.read(header)
                if (read < 24 ||
                    !header.copyOfRange(0, 4).contentEquals(QueryLogRing.magic) ||
                    readU32(header, 12) != capacity
                ) {
                    needsInit = true
                }
            }
        }

        if (needsInit) {
            val header = ByteArray(QueryLogRing.HEADER_SIZE.toInt())
            QueryLogRing.magic.copyInto(header, 0)
            writeU32(header, 4, QueryLogRing.VERSION)
            writeU32(header, 8, QueryLogRecord.RECORD_SIZE)
            writeU32(header, 12, capacity)
            // cursor at 16 stays zero
            file.writeBytes(header)
        }

        handle = RandomAccessFile(file, "rw")
        handle.seek(16)
        val cursorBytes = ByteArray(8)
        cursor = if (handle.read(cursorBytes) == 8) readU64(cursorBytes, 0) else 0L

        if (needsInit) {
            handle.setLength(QueryLogRing.HEADER_SIZE + capacity.toLong() * QueryLogRecord.RECORD_SIZE)
        }
    }

    val pendingCount: Int get() = pending.size

    fun append(record: QueryLogRecord) {
        pending.add(record)
        if (pending.size >= flushThreshold) {
            runCatching { flush() }
        }
    }

    fun flush() {
        if (pending.isEmpty()) return
        val records = ArrayList(pending)
        pending.clear()

        // Records may wrap the ring once; emit one or two contiguous writes.
        var index = 0
        while (index < records.size) {
            val slot = ((cursor + index) % capacity).toInt()
            val slotsUntilWrap = capacity - slot
            val batch = minOf(slotsUntilWrap, records.size - index)

            val buffer = ByteArray(batch * QueryLogRecord.RECORD_SIZE)
            for (i in 0 until batch) {
                records[index + i].encoded().copyInto(buffer, i * QueryLogRecord.RECORD_SIZE)
            }

            handle.seek(QueryLogRing.HEADER_SIZE + slot.toLong() * QueryLogRecord.RECORD_SIZE)
            handle.write(buffer)
            index += batch
        }

        cursor += records.size
        val cursorBytes = ByteArray(8)
        writeU64(cursorBytes, 0, cursor)
        handle.seek(16)
        handle.write(cursorBytes)
    }

    override fun close() {
        runCatching { flush() }
        runCatching { handle.close() }
    }

    companion object {
        fun openOrNull(file: File, capacity: Int = QueryLogRing.DEFAULT_CAPACITY): QueryLogRingWriter? = try {
            QueryLogRingWriter(file, capacity)
        } catch (_: Exception) {
            null
        }

        internal fun readU32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        internal fun readU64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }

        internal fun writeU32(out: ByteArray, offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        internal fun writeU64(out: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) {
                out[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
            }
        }
    }
}

class QueryLogRingReader(private val file: File) {

    data class Tail(val records: List<QueryLogRecord>, val cursor: Long)

    /**
     * Tail of the ring: all records with index in [max(since, cursor-capacity), cursor),
     * oldest first, capped at [limit] newest. Returns the new cursor to pass
     * back on the next poll.
     */
    fun read(since: Long = 0, limit: Int = 4000): Tail {
        if (!file.isFile) return Tail(emptyList(), since)
        return try {
            RandomAccessFile(file, "r").use { handle ->
                val header = ByteArray(24)
                if (handle.read(header) != 24 ||
                    !header.copyOfRange(0, 4).contentEquals(QueryLogRing.magic)
                ) {
                    return Tail(emptyList(), since)
                }
                val capacity = QueryLogRingWriter.readU32(header, 12).toLong()
                if (capacity <= 0) return Tail(emptyList(), since)
                val cursor = QueryLogRingWriter.readU64(header, 16)
                if (cursor <= since) return Tail(emptyList(), cursor)

                var start = maxOf(since, if (cursor > capacity) cursor - capacity else 0)
                if (cursor - start > limit) start = cursor - limit

                val records = ArrayList<QueryLogRecord>((cursor - start).toInt())
                var index = start
                while (index < cursor) {
                    val slot = index % capacity
                    val slotsUntilWrap = capacity - slot
                    val batch = minOf(slotsUntilWrap, cursor - index)

                    handle.seek(QueryLogRing.HEADER_SIZE + slot * QueryLogRecord.RECORD_SIZE)
                    val buffer = ByteArray((batch * QueryLogRecord.RECORD_SIZE).toInt())
                    val read = handle.read(buffer)
                    if (read <= 0) break

                    var offset = 0
                    while (offset + QueryLogRecord.RECORD_SIZE <= read) {
                        QueryLogRecord.decode(buffer, offset)?.let { records.add(it) }
                        offset += QueryLogRecord.RECORD_SIZE
                    }
                    index += batch
                }
                Tail(records, cursor)
            }
        } catch (_: Exception) {
            Tail(emptyList(), since)
        }
    }
}
