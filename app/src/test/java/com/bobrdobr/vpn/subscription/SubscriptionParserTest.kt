package com.bobrdobr.vpn.subscription

import org.bobrdobr.vpn.core.model.VpnProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {

    @Test
    fun testParseRemnawaveJsonActive() {
        val json = """
            {
              "status": "active",
              "is_frozen": 0,
              "cabinet_url": "https://vpn.example.com/cabinet",
              "user": {
                "id": "12345",
                "username": "user_bobr",
                "role": "vip",
                "expires_at": 1995000000,
                "is_frozen": 0
              },
              "connections": [
                {
                  "id": "conn-awg-1",
                  "name": "DE - Frankfurt AmneziaWG",
                  "proto": "awg",
                  "endpoint": "185.196.220.14:51820",
                  "address": "10.8.0.2/32",
                  "jc": 4, "jmin": 40, "jmax": 70,
                  "s1": 28, "s2": 44,
                  "h1": 1, "h2": 2, "h3": 3, "h4": 4
                },
                {
                  "id": "conn-oc-2",
                  "name": "NL - Amsterdam AnyConnect",
                  "proto": "openconnect",
                  "server": "194.26.29.80",
                  "port": 443
                }
              ]
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json, "https://sub.example.com")
        assertEquals(SubscriptionStatus.ACTIVE, info.status)
        assertEquals(0, info.isFrozen)
        assertTrue(info.isActive)
        assertFalse(info.isAccountBlocked)
        assertFalse(info.isSubscriptionExpired)
        assertEquals("user_bobr", info.username)
        assertEquals(2, info.profiles.size)

        val awgProfile = info.profiles[0]
        assertEquals(VpnProtocol.AMNEZIA_WG, awgProfile.protocol)
        assertEquals("185.196.220.14", awgProfile.serverAddress)
        assertEquals(51820, awgProfile.serverPort)
        assertEquals("DE", awgProfile.countryCode)

        val ocProfile = info.profiles[1]
        assertEquals(VpnProtocol.OPENCONNECT, ocProfile.protocol)
        assertEquals("194.26.29.80", ocProfile.serverAddress)
        assertEquals(443, ocProfile.serverPort)
        assertEquals("NL", ocProfile.countryCode)
    }

    @Test
    fun testParseRemnawaveJsonBlocked() {
        val json = """
            {
              "status": "blocked",
              "is_frozen": 2,
              "cabinet_url": "https://vpn.example.com/cabinet",
              "user": {
                "id": "999",
                "username": "blocked_user",
                "is_frozen": 2
              },
              "connections": []
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(json)
        assertEquals(SubscriptionStatus.BLOCKED, info.status)
        assertEquals(2, info.isFrozen)
        assertFalse(info.isActive)
        assertTrue(info.isAccountBlocked)
    }

    @Test
    fun testParseRawAwgConfig() {
        val conf = """
            [Interface]
            Address = 10.8.0.2/32
            DNS = 1.1.1.1
            PrivateKey = aGVsbG93b3JsZHByaXZhdGVrZXkxMjM0NTY3ODkwMTI=
            Jc = 4
            Jmin = 40
            Jmax = 70
            S1 = 28
            S2 = 44
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            [Peer]
            PublicKey = cHVibGlja2V5MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=
            Endpoint = 95.217.18.99:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val profile = SubscriptionParser.parseRawAwgConfig(conf, "Финляндия AWG")
        assertEquals(VpnProtocol.AMNEZIA_WG, profile.protocol)
        assertEquals("95.217.18.99", profile.serverAddress)
        assertEquals(51820, profile.serverPort)
        assertEquals("FI", profile.countryCode)
    }

    @Test
    fun testParsePersonaSubscriptionWithDynamicCabinetAndDns() {
        val payload = """
            {
              "status": "active",
              "user": {
                "username": "Egor",
                "role": "user",
                "expires_at": "2026-12-04T23:59:59"
              },
              "server": {
                "host": "bobr.netphoenix.ru",
                "app_version": "1.1.0"
              },
              "connections": [
                {
                  "id": 1,
                  "proto": "awg",
                  "name": "Egor_awg1",
                  "mode": "split",
                  "config_text": "[Interface]\nAddress = 10.8.0.2/32\nPrivateKey = aGVsbG93b3JsZHByaXZhdGVrZXkxMjM0NTY3ODkwMTI=\nDNS = 10.8.0.1\n\n[Peer]\nPublicKey = cHVibGlja2V5MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=\nEndpoint = bobr.netphoenix.ru:51820\nAllowedIPs = 0.0.0.0/0\n",
                  "ip": "10.8.0.2"
                },
                {
                  "id": 2,
                  "proto": "ocserv",
                  "name": "Egor_oc1",
                  "mode": "split",
                  "group": "split",
                  "uri": "anyconnect://bobr.netphoenix.ru?username=Egor_oc1",
                  "username": "Egor_oc1",
                  "password": "secret_password"
                }
              ]
            }
        """.trimIndent()

        val subUrl = "https://persona.netphoenix.ru/api/v1/sub/o5e4uglOoqoJMjaRUmAGRu5z_2_EcGp3L7Li2IVb5pw"
        val info = SubscriptionParser.parse(payload, subUrl)

        assertEquals(SubscriptionStatus.ACTIVE, info.status)
        assertEquals("Egor", info.username)
        assertEquals("user", info.role)
        assertEquals("bobr.netphoenix.ru", info.serverHost)
        assertEquals("https://persona.netphoenix.ru/cloud", info.cabinetUrl)
        assertTrue(info.expiresAt > 0)
        assertEquals(2, info.profiles.size)

        // 1. AmneziaWG connection with strict DNS from config
        val awg = info.profiles[0]
        assertEquals(VpnProtocol.AMNEZIA_WG, awg.protocol)
        assertEquals("bobr.netphoenix.ru", awg.serverAddress)
        assertEquals(51820, awg.serverPort)
        assertEquals(listOf("10.8.0.1"), awg.dnsServers)

        // 2. ocserv AnyConnect connection
        val oc = info.profiles[1]
        assertEquals(VpnProtocol.OPENCONNECT, oc.protocol)
        assertEquals("bobr.netphoenix.ru", oc.serverAddress)
        assertEquals(443, oc.serverPort)
        assertEquals("Egor_oc1", oc.username)
        assertEquals("secret_password", oc.password)
        assertEquals("split", oc.group)
        assertEquals("split", oc.secret)
    }

    @Test
    fun testParseOcservCamouflageSecretAndAwgDualStackAddress() {
        val payload = """
            {
              "status": "active",
              "connections": [
                {
                  "id": 10,
                  "proto": "awg",
                  "name": "DualStack AWG",
                  "config_text": "[Interface]\nAddress = 10.8.0.2/24, fd00:8:0::2/64\nPrivateKey = aGVsbG93b3JsZHByaXZhdGVrZXkxMjM0NTY3ODkwMTI=\nDNS = 10.8.0.1\n\n[Peer]\nPublicKey = cHVibGlja2V5MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=\nEndpoint = 194.26.29.80:51820\nAllowedIPs = 0.0.0.0/0\n"
                },
                {
                  "id": 20,
                  "proto": "ocserv",
                  "name": "Camouflage OC",
                  "uri": "anyconnect://bobr.netphoenix.ru:443/?kurwa&username=bobr_user",
                  "username": "bobr_user",
                  "password": "pass"
                }
              ]
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(payload)
        assertEquals(2, info.profiles.size)

        val awg = info.profiles[0]
        val awgServer = awg.toServerProfile()
        val awgTunnelConfig = awgServer.toTunnelConfig()
        assertEquals("10.8.0.2", awgTunnelConfig.ipv4Address)
        assertEquals(24, awgTunnelConfig.ipv4Prefix)

        val oc = info.profiles[1]
        assertEquals("kurwa", oc.secret)
        val ocServer = oc.toServerProfile()
        assertEquals("kurwa", ocServer.secret)
        val ocTunnelConfig = ocServer.toTunnelConfig()
        val json = org.json.JSONObject(ocTunnelConfig.rawConfigJson)
        assertEquals("kurwa", json.getString("secret"))
        assertEquals("kurwa", json.getString("secretKey"))
    }
}
