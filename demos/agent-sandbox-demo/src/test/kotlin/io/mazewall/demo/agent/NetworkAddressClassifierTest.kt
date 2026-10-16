package io.mazewall.demo.agent

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals

class NetworkAddressClassifierTest {
    @Test
    fun `IPv4 classification allows only global unicast destinations`() {
        mapOf(
            "0.0.0.0" to AddressDecision.DENY,
            "9.255.255.255" to AddressDecision.ALLOW,
            "10.0.0.0" to AddressDecision.DENY,
            "10.255.255.255" to AddressDecision.DENY,
            "11.0.0.0" to AddressDecision.ALLOW,
            "100.63.255.255" to AddressDecision.ALLOW,
            "100.64.0.0" to AddressDecision.DENY,
            "100.127.255.255" to AddressDecision.DENY,
            "100.128.0.0" to AddressDecision.ALLOW,
            "127.0.0.0" to AddressDecision.DENY,
            "127.255.255.255" to AddressDecision.DENY,
            "169.254.0.0" to AddressDecision.DENY,
            "169.254.169.254" to AddressDecision.DENY,
            "169.254.255.255" to AddressDecision.DENY,
            "172.15.255.255" to AddressDecision.ALLOW,
            "172.16.0.0" to AddressDecision.DENY,
            "172.31.255.255" to AddressDecision.DENY,
            "172.32.0.0" to AddressDecision.ALLOW,
            "192.0.2.1" to AddressDecision.DENY,
            "192.168.0.0" to AddressDecision.DENY,
            "192.168.255.255" to AddressDecision.DENY,
            "198.18.0.0" to AddressDecision.DENY,
            "198.19.255.255" to AddressDecision.DENY,
            "198.51.100.1" to AddressDecision.DENY,
            "203.0.113.1" to AddressDecision.DENY,
            "223.255.255.255" to AddressDecision.ALLOW,
            "224.0.0.0" to AddressDecision.DENY,
            "239.255.255.255" to AddressDecision.DENY,
            "240.0.0.0" to AddressDecision.DENY,
            "255.255.255.255" to AddressDecision.DENY,
        ).forEach { (address, expected) ->
            assertEquals(expected, NetworkAddressClassifier.classify(sockaddr(address)), address)
        }
    }

    @Test
    fun `IPv6 classification allows only global unicast destinations`() {
        mapOf(
            "::" to AddressDecision.DENY,
            "::1" to AddressDecision.DENY,
            "100::1" to AddressDecision.DENY,
            "2001:2::1" to AddressDecision.DENY,
            "2001:db8::1" to AddressDecision.DENY,
            "2001:4860:4860::8888" to AddressDecision.ALLOW,
            "3fff::1" to AddressDecision.DENY,
            "fc00::1" to AddressDecision.DENY,
            "fdff::1" to AddressDecision.DENY,
            "fe80::1" to AddressDecision.DENY,
            "febf:ffff::1" to AddressDecision.DENY,
            "fec0::1" to AddressDecision.DENY,
            "ff00::1" to AddressDecision.DENY,
            "ffff::1" to AddressDecision.DENY,
        ).forEach { (address, expected) ->
            assertEquals(expected, NetworkAddressClassifier.classify(sockaddr(address)), address)
        }
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
