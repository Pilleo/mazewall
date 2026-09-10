package io.mazewall.demo.agent

internal enum class AddressDecision {
    ALLOW,
    DENY,
}

/** Pure fail-closed classifier for the native sockaddr snapshots delivered by USER_NOTIF. */
internal object NetworkAddressClassifier {
    fun classify(sockaddr: ByteArray): AddressDecision {
        if (sockaddr.size < 2) return AddressDecision.DENY
        val family = (sockaddr[0].toInt() and 0xff) or ((sockaddr[1].toInt() and 0xff) shl 8)
        return when (family) {
            AF_INET -> classifyIpv4(sockaddr)
            AF_INET6 -> classifyIpv6(sockaddr)
            else -> AddressDecision.DENY
        }
    }

    private fun classifyIpv4(bytes: ByteArray): AddressDecision {
        if (bytes.size < IPV4_SOCKADDR_SIZE) return AddressDecision.DENY
        return if (isRestrictedIpv4(bytes[4].u8(), bytes[5].u8(), bytes[6].u8())) {
            AddressDecision.DENY
        } else {
            AddressDecision.ALLOW
        }
    }

    private fun classifyIpv6(bytes: ByteArray): AddressDecision {
        if (bytes.size < IPV6_SOCKADDR_SIZE) return AddressDecision.DENY
        val address = bytes.copyOfRange(8, 24)
        if (address.copyOfRange(0, 10).all { it == 0.toByte() } && address[10] == 0xff.toByte() && address[11] == 0xff.toByte()) {
            return if (isRestrictedIpv4(address[12].u8(), address[13].u8(), address[14].u8())) {
                AddressDecision.DENY
            } else {
                AddressDecision.ALLOW
            }
        }
        val globallyRouted = address[0].u8() and 0xe0 == 0x20
        val benchmarking = address.hasPrefix(48, 0x20, 0x01, 0x00, 0x02, 0x00, 0x00)
        val documentation =
            address.hasPrefix(32, 0x20, 0x01, 0x0d, 0xb8) ||
                address.hasPrefix(20, 0x3f, 0xff, 0x00)
        val orchid =
            address.hasPrefix(28, 0x20, 0x01, 0x00, 0x10) ||
                address.hasPrefix(28, 0x20, 0x01, 0x00, 0x20)
        return if (globallyRouted && !benchmarking && !documentation && !orchid) {
            AddressDecision.ALLOW
        } else {
            AddressDecision.DENY
        }
    }

    private fun isRestrictedIpv4(first: Int, second: Int, third: Int): Boolean =
        first == 0 ||
            first == 10 ||
            first == 100 && second in 64..127 ||
            first == 127 ||
            first == 169 && second == 254 ||
            first == 172 && second in 16..31 ||
            first == 192 && second == 0 && (third == 0 || third == 2) ||
            first == 192 && second == 88 && third == 99 ||
            first == 192 && second == 168 ||
            first == 198 && second in 18..19 ||
            first == 198 && second == 51 && third == 100 ||
            first == 203 && second == 0 && third == 113 ||
            first >= 224

    private fun ByteArray.hasPrefix(
        prefixBits: Int,
        vararg expected: Int,
    ): Boolean {
        val completeBytes = prefixBits / 8
        if ((0 until completeBytes).any { this[it].u8() != expected[it] }) return false
        val remainingBits = prefixBits % 8
        if (remainingBits == 0) return true
        val mask = 0xff shl (8 - remainingBits) and 0xff
        return this[completeBytes].u8() and mask == expected[completeBytes] and mask
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private const val AF_INET = 2
    private const val AF_INET6 = 10
    private const val IPV4_SOCKADDR_SIZE = 16
    private const val IPV6_SOCKADDR_SIZE = 28
}
