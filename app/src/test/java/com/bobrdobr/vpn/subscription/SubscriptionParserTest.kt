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
        assertTrue(info.status is SubscriptionStatus.Active)
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
        assertEquals(SubscriptionStatus.BlockedByAdmin, info.status)
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

        assertTrue(info.status is SubscriptionStatus.Active)
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
        assertEquals("", oc.secret)
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

    @Test
    fun testParseOfficialReferenceSubscriptionJson() {
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
                "app_version": "1.2.0"
              },
              "connections": [
                {
                  "id": 1,
                  "proto": "awg",
                  "name": "Egor_awg1",
                  "mode": "split",
                  "ip": "10.8.0.2",
                  "ipv6": "fd00:8:0::2",
                  "endpoint": "bobr.netphoenix.ru:443",
                  "dns": "10.8.0.1",
                  "allowed_ips": "0.0.0.0/0, ::/0",
                  "obfuscation": {
                    "Jc": 4,
                    "Jmin": 40,
                    "Jmax": 70,
                    "S1": 15,
                    "S2": 45,
                    "H1": 1,
                    "H2": 2,
                    "H3": 3,
                    "H4": 4
                  },
                  "config_text": "[Interface]\nPrivateKey = aGVsbG93b3JsZHByaXZhdGVrZXkxMjM0NTY3ODkwMTI=\n\n[Peer]\nPublicKey = cHVibGlja2V5MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=\n"
                },
                {
                  "id": 2,
                  "proto": "ocserv",
                  "name": "Egor_oc1",
                  "host": "bobr.netphoenix.ru",
                  "port": 443,
                  "mode": "split",
                  "group": "split",
                  "username": "Egor_oc1",
                  "password": "my_password",
                  "camouflage": {
                    "enabled": true,
                    "secret": "my_camouflage_token",
                    "path": "/?my_camouflage_token",
                    "query": "?my_camouflage_token",
                    "connect_url": "https://bobr.netphoenix.ru:443/?my_camouflage_token",
                    "host_with_camouflage": "bobr.netphoenix.ru:443/?my_camouflage_token",
                    "tls_sni": "bobr.netphoenix.ru",
                    "dtls": true
                  },
                  "uri": "anyconnect://bobr.netphoenix.ru:443/?my_camouflage_token&username=Egor_oc1"
                }
              ]
            }
        """.trimIndent()

        val info = SubscriptionParser.parse(payload, "https://bobr.netphoenix.ru/sub/test")
        assertTrue(info.status is SubscriptionStatus.Active)
        assertEquals("Egor", info.username)
        assertEquals("user", info.role)
        assertEquals("bobr.netphoenix.ru", info.serverHost)
        assertTrue(info.isActive)
        assertEquals(2, info.profiles.size)

        // 1. Verify AmneziaWG enriched profile
        val awg = info.profiles[0]
        assertEquals(VpnProtocol.AMNEZIA_WG, awg.protocol)
        assertEquals("bobr.netphoenix.ru", awg.serverAddress)
        assertEquals(443, awg.serverPort)
        assertNotNull(awg.configText)
        val text = awg.configText!!
        assertTrue(text.contains("Jc = 4"))
        assertTrue(text.contains("Jmin = 40"))
        assertTrue(text.contains("Jmax = 70"))
        assertTrue(text.contains("S1 = 15"))
        assertTrue(text.contains("S2 = 45"))
        assertTrue(text.contains("H1 = 1"))
        assertTrue(text.contains("DNS = 10.8.0.1"))
        assertTrue(text.contains("Address = 10.8.0.2/32, fd00:8:0::2/128"))
        assertTrue(text.contains("Endpoint = bobr.netphoenix.ru:443"))
        assertTrue(text.contains("AllowedIPs = 0.0.0.0/0, ::/0"))

        val awgServer = awg.toServerProfile()
        val awgTunnelConfig = awgServer.toTunnelConfig()
        assertEquals("10.8.0.2", awgTunnelConfig.ipv4Address)
        assertTrue(awgTunnelConfig.rawConfigJson.contains("Jc = 4"))
        assertTrue(awgTunnelConfig.rawConfigJson.contains("Endpoint = bobr.netphoenix.ru:443"))

        // 2. Verify OpenConnect profile with Camouflage Secret
        val oc = info.profiles[1]
        assertEquals(VpnProtocol.OPENCONNECT, oc.protocol)
        assertEquals("bobr.netphoenix.ru", oc.serverAddress)
        assertEquals(443, oc.serverPort)
        assertEquals("Egor_oc1", oc.username)
        assertEquals("my_password", oc.password)
        assertEquals("split", oc.group)
        assertEquals("my_camouflage_token", oc.secret)

        val ocServer = oc.toServerProfile()
        assertEquals("my_camouflage_token", ocServer.secret)
        assertEquals("split", ocServer.group)

        val ocTunnelConfig = ocServer.toTunnelConfig()
        val ocJson = org.json.JSONObject(ocTunnelConfig.rawConfigJson)
        assertEquals("my_camouflage_token", ocJson.getString("secret"))
        assertEquals("my_camouflage_token", ocJson.getString("secretKey"))
        assertEquals("split", ocJson.getString("group"))
        assertEquals("bobr.netphoenix.ru", ocJson.getString("server"))
        assertEquals(443, ocJson.getInt("port"))
    }
}
