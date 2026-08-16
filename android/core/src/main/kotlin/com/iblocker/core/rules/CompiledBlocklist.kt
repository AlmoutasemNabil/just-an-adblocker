package com.iblocker.core.rules

import java.io.File

/**
 * The compiled on-disk blocklist format shared between the app (writer) and
 * the VPN service (reader). Designed to be mmap'd: the packet path never
 * materializes the domain set on the heap.
 *
 *     offset 0   magic "IBK1"                    (4 bytes)
 *     offset 4   format version, u32 LE          (= 1)
 *     offset 8   entry count, u32 LE
 *     offset 12  generation, u32 LE              (monotonic, for change detection)
 *     offset 16  reserved, 16 zero bytes
 *     offset 32  count x u64 LE FNV-1a hashes, ascending
 *
 * Byte-compatible with the iOS build's blobs on purpose: one format, one set
 * of tests, and a blocklist compiled on either platform reads on the other.
 */
object CompiledBlocklist {
    val magic = "IBK1".toByteArray(Charsets.US_ASCII)
    const val FORMAT_VERSION: UInt = 1u
    const val HEADER_SIZE = 32

    @OptIn(ExperimentalUnsignedTypes::class)
    fun serialize(hashes: Collection<ULong>, generation: UInt): ByteArray {
        val sorted = hashes.toHashSet().toULongArray()
        sorted.sort()

        val out = ByteArray(HEADER_SIZE + sorted.size * 8)
        magic.copyInto(out, 0)
        writeU32(out, 4, FORMAT_VERSION)
        writeU32(out, 8, sorted.size.toUInt())
        writeU32(out, 12, generation)
        // offset 16..31 stays zero (reserved)
        var offset = HEADER_SIZE
        for (hash in sorted) {
            writeU64(out, offset, hash)
            offset += 8
        }
        return out
    }

    /** Atomically writes the compiled blob so a reader never sees a half-written file. */
    fun write(hashes: Collection<ULong>, generation: UInt, to: File) {
        writeAtomically(to, serialize(hashes, generation))
    }

    /** Write-to-temp + rename, the POSIX equivalent of Data.write(options: .atomic). */
    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            // Rename can fail if the destination exists on some filesystems.
            target.delete()
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
        }
    }

    fun writeU32(out: ByteArray, offset: Int, value: UInt) {
        out[offset] = (value and 0xFFu).toByte()
        out[offset + 1] = ((value shr 8) and 0xFFu).toByte()
        out[offset + 2] = ((value shr 16) and 0xFFu).toByte()
        out[offset + 3] = ((value shr 24) and 0xFFu).toByte()
    }

    fun writeU64(out: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            out[offset + i] = ((value shr (i * 8)) and 0xFFuL).toByte()
        }
    }

    fun readU32(bytes: ByteArray, offset: Int): UInt =
        (bytes[offset].toUInt() and 0xFFu) or
            ((bytes[offset + 1].toUInt() and 0xFFu) shl 8) or
            ((bytes[offset + 2].toUInt() and 0xFFu) shl 16) or
            ((bytes[offset + 3].toUInt() and 0xFFu) shl 24)

    fun readU64(bytes: ByteArray, offset: Int): ULong {
        var value = 0uL
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toULong() and 0xFFuL)
        }
        return value
    }
}
