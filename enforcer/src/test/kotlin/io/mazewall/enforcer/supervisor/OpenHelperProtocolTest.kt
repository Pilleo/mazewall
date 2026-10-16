package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class OpenHelperProtocolTest {
    @Test
    fun `open rejects oversized malformed absolute and NUL path snapshots`() {
        val paths = listOf("/absolute".toByteArray(), byteArrayOf(0), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(4096) { 97 })
        for (path in paths) {
            val frame = ByteArrayOutputStream()
                .also { bytes ->
                DataOutputStream(bytes).use {
                    it.writeByte(3)
                    it.writeInt(path.size)
                    it.write(path)
                    repeat(3) { _ -> it.writeLong(0) }
                }
            }.toByteArray()
            assertThrows(IOException::class.java) { SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(frame))) }
        }
    }

    @Test
    fun `empty pathname is preserved for kernel error validation`() {
        val frame = ByteArrayOutputStream()
            .also { bytes ->
            DataOutputStream(bytes).use {
                it.writeByte(3)
                it.writeInt(0)
                repeat(3) { _ -> it.writeLong(0) }
            }
        }.toByteArray()
        val operation = assertDoesNotThrow<SocketOperation> {
            SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(frame)))
        }
        assertEquals("", assertInstanceOf(SocketOperation.OpenAt2::class.java, operation).path)
    }

    @Test
    fun `explicit dirfd open snapshot roundtrips without losing ABI arguments`() {
        val frame = ByteArrayOutputStream()
            .also { bytes ->
            DataOutputStream(bytes).use {
                it.writeByte(3)
                val path = "relative/λ".toByteArray(Charsets.UTF_8)
                it.writeInt(path.size)
                it.write(path)
                it.writeLong(0x80000)
                it.writeLong(0x180)
                it.writeLong(8)
            }
        }.toByteArray()
        val operation = assertDoesNotThrow<SocketOperation> {
            SocketHelperProtocol.readOperation(DataInputStream(ByteArrayInputStream(frame)))
        }
        val encoded = ByteArrayOutputStream()
        SocketHelperProtocol.writeOperation(DataOutputStream(encoded), operation)
        assertArrayEquals(frame, encoded.toByteArray())
    }
}
