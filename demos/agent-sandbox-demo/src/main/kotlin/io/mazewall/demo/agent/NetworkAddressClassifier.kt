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
        return if (isRestrictedIpv4(bytes[4].u8(), bytes[5].u8())) AddressDecision.DENY else AddressDecision.ALLOW
    }

    private fun classifyIpv6(bytes: ByteArray): AddressDecision {
        if (bytes.size < IPV6_SOCKADDR_SIZE) return AddressDecision.DENY
        val address = bytes.copyOfRange(8, 24)
        if (address.copyOfRange(0, 10).all { it == 0.toByte() } && address[10] == 0xff.toByte() && address[11] == 0xff.toByte()) {
            return if (isRestrictedIpv4(address[12].u8(), address[13].u8())) AddressDecision.DENY else AddressDecision.ALLOW
        }
        val loopback = address.take(15).all { it == 0.toByte() } && address[15] == 1.toByte()
        val unspecified = address.all { it == 0.toByte() }
        val first = address[0].u8()
        val second = address[1].u8()
        val uniqueLocal = first and 0xfe == 0xfc
        val linkLocal = first == 0xfe && second and 0xc0 == 0x80
        return if (loopback || unspecified || uniqueLocal || linkLocal) AddressDecision.DENY else AddressDecision.ALLOW
    }

    private fun isRestrictedIpv4(first: Int, second: Int): Boolean =
        first == 0 ||
            first == 10 ||
            first == 127 ||
            first == 169 && second == 254 ||
            first == 172 && second in 16..31 ||
            first == 192 && second == 168

    private fun Byte.u8(): Int = toInt() and 0xff

    private const val AF_INET = 2
    private const val AF_INET6 = 10
    private const val IPV4_SOCKADDR_SIZE = 16
    private const val IPV6_SOCKADDR_SIZE = 28
}
