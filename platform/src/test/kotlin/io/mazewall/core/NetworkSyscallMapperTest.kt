package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class NetworkSyscallMapperTest {
    companion object {
        @JvmStatic
        fun networkMappings(): Stream<Arguments> =
            Stream.of(
                Arguments.of("connect", Syscall.CONNECT, Arch.AMD64.connect, Arch.AARCH64.connect),
                Arguments.of("bind", Syscall.BIND, Arch.AMD64.bind, Arch.AARCH64.bind),
                Arguments.of("listen", Syscall.LISTEN, Arch.AMD64.listen, Arch.AARCH64.listen),
                Arguments.of("accept", Syscall.ACCEPT, Arch.AMD64.accept, Arch.AARCH64.accept),
                Arguments.of("accept4", Syscall.ACCEPT4, Arch.AMD64.accept4, Arch.AARCH64.accept4),
                Arguments.of("sendto", Syscall.SENDTO, Arch.AMD64.sendto, Arch.AARCH64.sendto),
                Arguments.of("sendmsg", Syscall.SENDMSG, Arch.AMD64.sendmsg, Arch.AARCH64.sendmsg),
                Arguments.of("sendmmsg", Syscall.SENDMMSG, Arch.AMD64.sendmmsg, Arch.AARCH64.sendmmsg),
                Arguments.of("recvfrom", Syscall.RECVFROM, Arch.AMD64.recvfrom, Arch.AARCH64.recvfrom),
                Arguments.of("recvmmsg", Syscall.RECVMMSG, Arch.AMD64.recvmmsg, Arch.AARCH64.recvmmsg),
                Arguments.of("socket", Syscall.SOCKET, Arch.AMD64.socket, Arch.AARCH64.socket),
                Arguments.of("getsockopt", Syscall.GETSOCKOPT, Arch.AMD64.getsockopt, Arch.AARCH64.getsockopt),
                Arguments.of("setsockopt", Syscall.SETSOCKOPT, Arch.AMD64.setsockopt, Arch.AARCH64.setsockopt),
                Arguments.of("getsockname", Syscall.GETSOCKNAME, Arch.AMD64.getsockname, Arch.AARCH64.getsockname),
                Arguments.of("getpeername", Syscall.GETPEERNAME, Arch.AMD64.getpeername, Arch.AARCH64.getpeername),
            )

        @JvmStatic
        fun nonNetworkSyscalls(): Stream<Syscall> = Stream.of(Syscall.READ, Syscall.EXECVE, Syscall.MMAP)
    }

    @ParameterizedTest(name = "{0} maps on AMD64 and AARCH64")
    @MethodSource("networkMappings")
    fun `network syscall mapping preserves architecture number`(
        name: String,
        syscall: Syscall,
        expectedAmd64: Int,
        expectedAarch64: Int,
    ) {
        assertEquals(expectedAmd64, NetworkSyscallMapper.numberFor(syscall, Arch.AMD64))
        assertEquals(expectedAarch64, NetworkSyscallMapper.numberFor(syscall, Arch.AARCH64))
        assertEquals(expectedAmd64, syscall.numberFor(Arch.AMD64))
        assertEquals(expectedAarch64, syscall.numberFor(Arch.AARCH64))
    }

    @ParameterizedTest(name = "{0} is not a network syscall")
    @MethodSource("nonNetworkSyscalls")
    fun `network mapper rejects syscalls from other domains`(syscall: Syscall) {
        assertEquals(-1, NetworkSyscallMapper.numberFor(syscall, Arch.AMD64))
        assertEquals(-1, NetworkSyscallMapper.numberFor(syscall, Arch.AARCH64))
    }
}
