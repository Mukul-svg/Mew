package com.past9.phoneaos.web

import com.past9.phoneaos.agent.sharedHttp
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Search results are untrusted URLs, and this phone runs local automation and MCP servers. Fetch only public
 * addresses: a page must never be able to make the agent read 127.0.0.1, the LAN, or a cloud metadata endpoint.
 *
 * The check is in the DNS layer, so it covers redirects too: OkHttp asks this Dns for every connection it makes.
 */
object UrlGuard {
    private val blockedNames = setOf("localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback")

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address
        if (b.size == 4) {
            val a = b[0].toInt() and 0xff
            val c = b[1].toInt() and 0xff
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 169.254.0.0/16, 192.0.0.0/24, 198.18.0.0/15, 224.0.0.0/4
            if (a == 0 || a == 224 || a >= 240) return false
            if (a == 100 && c in 64..127) return false
            if (a == 169 && c == 254) return false
            if (a == 192 && c == 0) return false
            if (a == 198 && (c == 18 || c == 19)) return false
        } else if (b.size == 16) {
            // fc00::/7 unique-local, fe80::/10 link-local
            val first = b[0].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return false
            if (first == 0xfe && (b[1].toInt() and 0xc0) == 0x80) return false
        }
        return true
    }

    fun check(url: String) {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: throw IllegalArgumentException("not a URL")
        if (u.scheme?.lowercase() !in setOf("http", "https")) throw IllegalArgumentException("only http(s) URLs are fetched")
        val host = u.host?.lowercase()?.trimEnd('.') ?: throw IllegalArgumentException("not a URL")
        if (host in blockedNames) throw IllegalArgumentException("private address")
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { throw IllegalArgumentException("could not resolve ${u.host}") }
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw IllegalArgumentException("private address")
    }

    class GuardedDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.lowercase().trimEnd('.')
            if (host in blockedNames) throw UnknownHostException("$hostname is private")
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("$hostname resolves to a private address")
            return addresses
        }
    }

    /** The HTTP client for arbitrary web content. Provider APIs use the ordinary shared client. */
    val http: OkHttpClient by lazy { sharedHttp.newBuilder().dns(GuardedDns()).build() }
}
