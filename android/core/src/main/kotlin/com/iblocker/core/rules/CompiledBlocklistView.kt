package com.iblocker.core.rules

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only, memory-mapped view over a compiled blocklist blob.
 * Lookups binary-search the file-backed pages directly, so resident memory
 * stays tiny regardless of list size: 800k domains is 6.4 MB of *file*, of
 * which only the touched pages are ever resident.
 */
class CompiledBlocklistView private constructor(
    private val buffer: ByteBuffer,
    val count: Int,
    val generation: UInt,
) {
    class ReadException(message: String) : Exception(message)

    val isEmpty: Boolean get() = count == 0

    fun contains(hash: ULong): Boolean {
        if (count == 0) return false
        val target = hash.toLong()
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = buffer.getLong(CompiledBlocklist.HEADER_SIZE + mid * 8)
            val comparison = java.lang.Long.compareUnsigned(value, target)
            when {
                comparison == 0 -> return true
                comparison < 0 -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return false
    }

    companion object {
        /** Maps `file` read-only. Throws [ReadException] for a missing, short, or foreign file. */
        @Throws(ReadException::class)
        fun open(file: File): CompiledBlocklistView {
            if (!file.isFile) throw ReadException("missing file")
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < CompiledBlocklist.HEADER_SIZE) throw ReadException("corrupt: too short")

                val mapped = raf.channel
                    .map(FileChannel.MapMode.READ_ONLY, 0, length)
                    .order(ByteOrder.LITTLE_ENDIAN)

                val header = ByteArray(CompiledBlocklist.HEADER_SIZE)
                mapped.duplicate().get(header)

                for (i in CompiledBlocklist.magic.indices) {
                    if (header[i] != CompiledBlocklist.magic[i]) throw ReadException("bad magic")
                }
                val version = CompiledBlocklist.readU32(header, 4)
                if (version != CompiledBlocklist.FORMAT_VERSION) throw ReadException("unsupported version $version")

                val entryCount = CompiledBlocklist.readU32(header, 8).toInt()
                if (entryCount < 0 || length < CompiledBlocklist.HEADER_SIZE + entryCount.toLong() * 8) {
                    throw ReadException("corrupt: truncated entries")
                }
                return CompiledBlocklistView(mapped, entryCount, CompiledBlocklist.readU32(header, 12))
            }
        }

        /** Convenience for the many call sites that treat "no usable blob" as "no rules". */
        fun openOrNull(file: File): CompiledBlocklistView? = try {
            open(file)
        } catch (_: Exception) {
            null
        }
    }
}
