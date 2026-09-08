package io.mazewall.profiler.tierE.daemon

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Dependency-free ELF64 dynamic-symbol to file-offset resolver for uprobes. */
public object ElfSymbolOffset {
    public fun resolve(
        path: Path,
        symbol: String,
    ): Long = resolve(Files.readAllBytes(path), symbol)

    public fun resolve(
        image: ByteArray,
        symbol: String,
    ): Long {
        val elf = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
        require(image.size >= 64 && image.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
            "not an ELF file"
        }
        require(image[4] == 2.toByte() && image[5] == 1.toByte()) { "only little-endian ELF64 is supported" }
        val sectionOffset = elf.getLong(40)
        val sectionEntrySize = elf.getShort(58).toInt() and 0xffff
        val sectionCount = elf.getShort(60).toInt() and 0xffff
        require(sectionOffset >= 0 && sectionEntrySize >= 64 && sectionOffset + sectionEntrySize.toLong() * sectionCount <= image.size) {
            "invalid ELF section table"
        }
        repeat(sectionCount) { index ->
            val section = Math.toIntExact(sectionOffset + index.toLong() * sectionEntrySize)
            if (elf.getInt(section + 4) != SHT_DYNSYM) return@repeat
            val stringsIndex = elf.getInt(section + 40)
            require(stringsIndex in 0 until sectionCount) { "invalid ELF symbol string table" }
            val stringsSection = Math.toIntExact(sectionOffset + stringsIndex.toLong() * sectionEntrySize)
            val stringsOffset = elf.getLong(stringsSection + 24)
            val stringsSize = elf.getLong(stringsSection + 32)
            val symbolsOffset = elf.getLong(section + 24)
            val symbolsSize = elf.getLong(section + 32)
            val symbolSize = elf.getLong(section + 56)
            require(symbolSize >= 24 && symbolsOffset + symbolsSize <= image.size) { "invalid ELF dynamic symbol table" }
            var cursor = symbolsOffset
            while (cursor < symbolsOffset + symbolsSize) {
                val entry = Math.toIntExact(cursor)
                val nameOffset = elf.getInt(entry).toLong() and 0xffff_ffffL
                if (nameOffset < stringsSize && image.cString(stringsOffset + nameOffset) == symbol) {
                    return virtualAddressToOffset(elf, image.size, elf.getLong(entry + 8))
                }
                cursor += symbolSize
            }
        }
        throw IllegalArgumentException("ELF symbol not found: $symbol")
    }

    private fun virtualAddressToOffset(
        elf: ByteBuffer,
        imageSize: Int,
        address: Long,
    ): Long {
        val programOffset = elf.getLong(32)
        val entrySize = elf.getShort(54).toInt() and 0xffff
        val count = elf.getShort(56).toInt() and 0xffff
        require(programOffset >= 0 && entrySize >= 56 && programOffset + entrySize.toLong() * count <= imageSize) {
            "invalid ELF program table"
        }
        repeat(count) { index ->
            val header = Math.toIntExact(programOffset + index.toLong() * entrySize)
            if (elf.getInt(header) != PT_LOAD) return@repeat
            val fileOffset = elf.getLong(header + 8)
            val virtualAddress = elf.getLong(header + 16)
            val fileSize = elf.getLong(header + 32)
            if (address in virtualAddress until virtualAddress + fileSize) return fileOffset + address - virtualAddress
        }
        throw IllegalArgumentException("ELF symbol is not backed by a loadable file segment")
    }

    private fun ByteArray.cString(offset: Long): String {
        val start = Math.toIntExact(offset)
        require(start in indices) { "invalid ELF string offset" }
        var end = start
        while (end < size && this[end] != 0.toByte()) end++
        require(end < size) { "unterminated ELF string" }
        return String(this, start, end - start, Charsets.UTF_8)
    }

    private const val SHT_DYNSYM = 11
    private const val PT_LOAD = 1
}
