package io.mazewall.tierE.daemon

/**
 * Minimal ELF(64) reader for NT_GNU_BUILD_ID extraction — enough to bind the
 * tracing ABI to an identifiable binary without libelf on the Kotlin side.
 * Accepts the full file image; returns lowercase hex or null when absent.
 */
public object ElfBuildIdExtractor {

    private val MAGIC = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) // \x7fELF
    private const val EI_CLASS = 4
    private const val ELFCLASS64 = 2
    private const val E_SHOFF = 0x28
    private const val E_SHENTSIZE = 0x3A
    private const val E_SHNUM = 0x3C
    private const val E_SHSTRNDX = 0x3E
    private const val SH_NAME = 0
    private const val SH_TYPE = 4
    private const val SH_OFFSET = 24
    private const val SH_SIZE = 32
    private const val SHDR64_SIZE = 64
    private const val SHT_NOTE = 7
    private const val NT_GNU_BUILD_ID = 3
    private const val HEX = "0123456789abcdef"

    public fun extract(image: ByteArray): String? {
        if (image.size < 64 || !image.hasPrefix(MAGIC)) return null
        if (image[EI_CLASS] != ELFCLASS64.toByte()) return null

        val shoff = image.u64(E_SHOFF)
        val shentsize = image.u16(E_SHENTSIZE)
        val shnum = image.u16(E_SHNUM)
        val shstrndx = image.u16(E_SHSTRNDX)
        if (shoff <= 0L || shentsize < SHDR64_SIZE || shnum == 0 || shstrndx >= shnum) return null
        if (shoff + shnum.toLong() * shentsize > image.size) return null

        fun hdr(index: Int, field: Int): Long =
            image.u64((shoff + index.toLong() * shentsize + field).toInt())

        val strtabOffset = hdr(shstrndx, SH_OFFSET).toInt()
        if (strtabOffset !in image.indices) return null

        for (i in 0 until shnum) {
            if (hdr(i, SH_TYPE).toInt() != SHT_NOTE) continue
            var off = hdr(i, SH_OFFSET).toInt()
            val end = off + hdr(i, SH_SIZE).toInt()
            if (end > image.size) continue
            while (off + 12 <= end) {
                val namesz = image.i32(off)
                val descsz = image.i32(off + 4)
                val type = image.i32(off + 8)
                if (namesz < 0 || descsz < 0) break
                val nameAligned = aligned(namesz)
                val descAligned = aligned(descsz)
                if (off + 12 + nameAligned + descAligned > end) break
                val isBuildId = type == NT_GNU_BUILD_ID && namesz == 4 &&
                    image[off + 12] == 'G'.code.toByte() &&
                    image[off + 13] == 'N'.code.toByte() &&
                    image[off + 14] == 'U'.code.toByte() &&
                    image[off + 15] == 0.toByte()
                if (isBuildId && descsz in 1..20) {
                    val sb = StringBuilder(descsz * 2)
                    repeat(descsz) { b ->
                        val v = image[off + 12 + nameAligned + b].toInt() and 0xFF
                        sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
                    }
                    return sb.toString()
                }
                off += 12 + nameAligned + descAligned
            }
        }
        return null
    }

    private fun aligned(v: Int): Int = (v + 3) and (Int.MAX_VALUE - 3)

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        prefix.forEachIndexed { i, b -> if (this[i] != b) return false }
        return true
    }

    private fun ByteArray.u64(off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (this[off + i].toLong() and 0xFF)
        return v
    }

    private fun ByteArray.i32(off: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (this[off + i].toInt() and 0xFF)
        return v
    }

    private fun ByteArray.u16(off: Int): Int {
        var v = 0
        for (i in 1 downTo 0) v = (v shl 8) or (this[off + i].toInt() and 0xFF)
        return v
    }
}
