package io.github.sparkcyf.voicesipagent.app

import java.net.Inet4Address
import java.net.NetworkInterface

/** What a local address is usable for, from the user's point of view. */
enum class AddressKind { MOBILE, WIFI, VPN_TAILSCALE, VPN_ZEROTIER, OTHER }

/** One usable IPv4 address of the gateway phone, with a plain-language label. */
data class LocalAddress(val kind: AddressKind, val label: String, val address: String)

/**
 * The gateway phone's addresses (feature: remote access). The built-in registrar already binds
 * 0.0.0.0, so it accepts registrations on every interface below — mobile data, hotspot and
 * any VPN. The UI shows each address with a copy button; the IP-change email reports the
 * primary one. IPv4 only: the gateway's SIP stack and Linphone both use IPv4 here.
 */
object LocalAddresses {
    fun classify(interfaceName: String): AddressKind {
        val name = interfaceName.lowercase()
        return when {
            name.startsWith("tailscale") -> AddressKind.VPN_TAILSCALE
            name.startsWith("zt") -> AddressKind.VPN_ZEROTIER
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("wwan") ||
                name.startsWith("pdp") || name.startsWith("uwbr") -> AddressKind.MOBILE
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth") ||
                name.startsWith("p2p") -> AddressKind.WIFI
            else -> AddressKind.OTHER
        }
    }

    fun labelFor(kind: AddressKind, interfaceName: String): String = when (kind) {
        AddressKind.MOBILE -> "Mobile data"
        AddressKind.WIFI -> "Wi-Fi / hotspot"
        AddressKind.VPN_TAILSCALE -> "Tailscale VPN"
        AddressKind.VPN_ZEROTIER -> "ZeroTier VPN"
        AddressKind.OTHER -> "Network ($interfaceName)"
    }

    /** Every usable IPv4 address, mobile first, then VPN, then Wi-Fi, then the rest. */
    fun list(): List<LocalAddress> {
        val found = ArrayList<LocalAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val kind = classify(iface.name)
                for (addr in iface.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    found += LocalAddress(kind, labelFor(kind, iface.name), addr.hostAddress ?: continue)
                }
            }
        } catch (e: Exception) {
            AppLog.w("listing local addresses failed: ${e.javaClass.simpleName}")
        }
        val order = mapOf(
            AddressKind.MOBILE to 0,
            AddressKind.VPN_TAILSCALE to 1,
            AddressKind.VPN_ZEROTIER to 2,
            AddressKind.WIFI to 3,
            AddressKind.OTHER to 4,
        )
        return found.sortedBy { order[it.kind] ?: 9 }
    }

    /** The address Linphone most likely registers to: the mobile-data address when present. */
    fun primary(addresses: List<LocalAddress> = list()): String? =
        addresses.firstOrNull { it.kind == AddressKind.MOBILE }?.address ?: addresses.firstOrNull()?.address

    fun vpnConnected(addresses: List<LocalAddress>): Boolean =
        addresses.any { it.kind == AddressKind.VPN_TAILSCALE || it.kind == AddressKind.VPN_ZEROTIER }
}
