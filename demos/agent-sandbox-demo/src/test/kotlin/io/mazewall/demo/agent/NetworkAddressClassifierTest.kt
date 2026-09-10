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
    fun `blocks IPv6 local addresses`() {
        listOf("::1", "fc00::1", "fdff::1", "fe80::1")
            .forEach { address ->
                assertEquals(AddressDecision.DENY, NetworkAddressClassifier.classify(sockaddr(address)), address)
            }
        assertEquals(AddressDecision.ALLOW, NetworkAddressClassifier.classify(sockaddr("2001:4860:4860::8888")))
    }

    @Test
    fun `classifies explicit IPv4 mapped IPv6 address bytes`() {
        assertEquals(
            AddressDecision.DENY,
            NetworkAddressClassifier.classify(mappedIpv6Sockaddr(169, 254, 169, 254)),
            "mapped metadata address",
        )
        assertEquals(
            AddressDecision.DENY,
            NetworkAddressClassifier.classify(mappedIpv6Sockaddr(192, 168, 1, 1)),
            "mapped RFC1918 address",
        )
        assertEquals(
            AddressDecision.ALLOW,
            NetworkAddressClassifier.classify(mappedIpv6Sockaddr(8, 8, 8, 8)),
            "mapped public address",
        )
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

    private fun mappedIpv6Sockaddr(vararg ipv4: Int): ByteArray {
        require(ipv4.size == 4)
        return ByteArray(28).also { bytes ->
            bytes[0] = 10 // AF_INET6, native little-endian family
            bytes[18] = 0xff.toByte()
            bytes[19] = 0xff.toByte()
            ipv4.forEachIndexed { index, octet -> bytes[20 + index] = octet.toByte() }
        }
    }
}
