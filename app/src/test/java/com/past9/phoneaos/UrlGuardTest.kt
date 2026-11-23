package com.past9.phoneaos

import com.past9.phoneaos.web.UrlGuard
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class UrlGuardTest {
    @Test fun privateAndLocalAddressesAreBlocked() {
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1")
            .forEach { assertFalse(it, UrlGuard.isPublic(InetAddress.getByName(it))) }
    }

    @Test fun publicAddressesPass() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")
            .forEach { assertTrue(it, UrlGuard.isPublic(InetAddress.getByName(it))) }
    }

    @Test fun checkRejectsLoopbackAndNonHttp() {
        assertThrows(IllegalArgumentException::class.java) { UrlGuard.check("http://127.0.0.1:47321/v1/health") }
        assertThrows(IllegalArgumentException::class.java) { UrlGuard.check("file:///etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { UrlGuard.check("http://localhost/") }
    }

    @Test fun guardedDnsRejectsAPrivateResolution() {
        val dns = UrlGuard.GuardedDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        })
        assertThrows(UnknownHostException::class.java) { dns.lookup("evil.test") }
    }

    @Test fun guardedDnsAllowsAPublicResolution() {
        val dns = UrlGuard.GuardedDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("8.8.8.8"))
        })
        assertEquals(1, dns.lookup("example.test").size)
    }
}
