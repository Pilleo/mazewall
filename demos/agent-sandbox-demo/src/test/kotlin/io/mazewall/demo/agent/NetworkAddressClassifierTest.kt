package io.mazewall.demo.agent

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals

class NetworkAddressClassifierTest {
    @Test
    fun `blocks metadata RFC1918 loopback and link local IPv4 boundaries`() {
        listOf(
            "169.254.169.254",
            "10.0.0.0",
            "10.255.255.255",
            "172.16.0.0",
            "172.31.255.255",
            "192.168.0.0",
            "192.168.255.255",
            "127.0.0.1",
        ).forEach { address ->
            assertEquals(AddressDecision.DENY, NetworkAddressClassifier.classify(sockaddr(address)), address)
        }
        assertEquals(AddressDecision.ALLOW, NetworkAddressClassifier.classify(sockaddr("172.15.255.255")))
        assertEquals(AddressDecision.ALLOW, NetworkAddressClassifier.classify(sockaddr("172.32.0.0")))
    }

    @Test
    fun `blocks IPv6 local and IPv4 mapped private addresses`() {
        listOf("::1", "fc00::1", "fdff::1", "fe80::1", "::ffff:169.254.169.254", "::ffff:192.168.1.1")
            .forEach { address ->
                assertEquals(AddressDecision.DENY, NetworkAddressClassifier.classify(sockaddr(address)), address)
            }
        assertEquals(AddressDecision.ALLOW, NetworkAddressClassifier.classify(sockaddr("2001:4860:4860::8888")))
    }

    @Test
    fun `malformed truncated and unsupported addresses fail closed`() {
        assertEquals(AddressDecision.DENY, NetworkAddressClassifier.classify(byteArrayOf()))
        assertEquals(AddressDecision.DENY, NetworkAddressClassifier.classify(byteArrayOf(2, 0, 0)))
        assertEquals(AddressDecision.DENY, NetworkAddressClassifier.classify(byteArrayOf(1, 0, 0, 0, 1, 2, 3, 4)))
    }

    private fun sockaddr(text: String): ByteArray {
        val address = InetAddress.getByName(text).address
        val family = if (address.size == 4) 2 else 10
        val bytes = ByteArray(if (family == 2) 16 else 28)
        bytes[0] = family.toByte()
        bytes[1] = 0
        address.copyInto(bytes, if (family == 2) 4 else 8)
        return bytes
    }
}
