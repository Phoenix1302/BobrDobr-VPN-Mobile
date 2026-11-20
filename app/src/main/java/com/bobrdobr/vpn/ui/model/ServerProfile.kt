package com.bobrdobr.vpn.ui.model

import com.bobrdobr.vpn.config.ConfigValidator
import com.bobrdobr.vpn.config.ValidationResult
import org.bobrdobr.vpn.core.model.CidrRoute
import org.bobrdobr.vpn.core.model.TunnelConfig
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Server profile presentation model for BobrDobr VPN Dam gateways.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val location: String = "Европа",
    val countryCode: String = "EU",
    val flagEmoji: String = "🌐",
    val protocol: VpnProtocol,
    val serverAddress: String,
    val serverPort: Int,
    val pingMs: Int = 0,
    val loadPercent: Int = 0,
    val isRecommended: Boolean = false,
    val dnsServers: List<String> = emptyList(),
    val configText: String? = null,
    val username: String? = null,
    val password: String? = null,
    val group: String? = null,
    val secret: String? = null,
    val isCustom: Boolean = false,
    val isFromSubscription: Boolean = false,
    val rawConfigJson: String? = null
) {
    fun toTunnelConfig(): TunnelConfig {
        var ipv4 = "10.8.0.2"
        var prefix = 32
        var effectiveDns = if (dnsServers.isNotEmpty()) dnsServers else listOf("1.1.1.1", "1.0.0.1")
        var serverAddr = serverAddress
        var serverP = serverPort

        var effectiveConfigText = configText
        if (!rawConfigJson.isNullOrBlank() && (protocol == VpnProtocol.AMNEZIA_WG || protocol == VpnProtocol.WIREGUARD)) {
            try {
                val json = JSONObject(rawConfigJson)
                var raw = effectiveConfigText ?: ""
                if (raw.isBlank()) {
                    for (key in listOf("config_text", "config_content", "config", "configText", "raw_config")) {
                        val embedded = json.optString(key, "")
                        if (embedded.isNotBlank()) {
                            raw = embedded
                            break
                        }
                    }
                }
                val endpoint = json.optString("endpoint", if (serverAddr.isNotBlank()) "$serverAddr:$serverP" else "")
                val ip = json.optString("ip", json.optString("address", ""))
                val ipv6 = json.optString("ipv6", "")
                val dns = json.optString("dns", if (effectiveDns.isNotEmpty()) effectiveDns.joinToString(", ") else "")
                val allowedIps = json.optString("allowed_ips", json.optString("allowedIps", ""))
                val obfObj = json.optJSONObject("obfuscation")
                val enriched = com.bobrdobr.vpn.subscription.SubscriptionParser.enrichAwgConfig(
                    raw, endpoint, ip, ipv6, dns, allowedIps, obfObj, json, serverAddr
                )
                if (enriched.isNotBlank()) {
                    effectiveConfigText = enriched
                }
            } catch (_: Exception) {}
        }

        if ((protocol == VpnProtocol.AMNEZIA_WG || protocol == VpnProtocol.WIREGUARD) && !effectiveConfigText.isNullOrBlank()) {
            val validation = ConfigValidator.validateAwg(effectiveConfigText)
            if (validation is ValidationResult.SuccessAwg) {
                if (validation.clientAddress.isNotBlank()) {
                    val parts = validation.clientAddress.split("/")
                    ipv4 = parts[0].trim()
                    prefix = parts.getOrNull(1)?.toIntOrNull() ?: 32
                }
                if (validation.dnsServers.isNotEmpty()) {
                    effectiveDns = validation.dnsServers
                }
                if (validation.serverAddress.isNotBlank()) {
                    serverAddr = validation.serverAddress
                    serverP = validation.serverPort
                }
            }
        }

        val effectiveRawJson = if ((protocol == VpnProtocol.AMNEZIA_WG || protocol == VpnProtocol.WIREGUARD) && !effectiveConfigText.isNullOrBlank()) {
            effectiveConfigText
        } else if (!rawConfigJson.isNullOrEmpty()) {
            try {
                val obj = JSONObject(rawConfigJson)
                if (protocol == VpnProtocol.OPENCONNECT) {
                    val camoObj = obj.optJSONObject("camouflage")
                    val effectiveSecret = secret
                        ?: camoObj?.optString("secret")?.takeIf { it.isNotBlank() }
                        ?: obj.optString("secret").takeIf { it.isNotBlank() }
                        ?: obj.optString("secretKey").takeIf { it.isNotBlank() }
                        ?: ""
                    val effectiveGroup = group
                        ?: obj.optString("group").takeIf { it.isNotBlank() }
                        ?: obj.optString("authgroup").takeIf { it.isNotBlank() }
                        ?: "split"
                    if (effectiveSecret.isNotBlank()) {
                        obj.put("secret", effectiveSecret)
                        obj.put("secretKey", effectiveSecret)
                    }
                    obj.put("group", effectiveGroup)
                    if (!obj.has("server")) obj.put("server", serverAddr)
                    if (!obj.has("port")) obj.put("port", serverP)
                    if (!serverAddr.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        if (!obj.has("tls_sni")) obj.put("tls_sni", serverAddr)
                        if (!obj.has("original_host")) obj.put("original_host", serverAddr)
                    }
                    username?.let { if (!obj.has("username")) obj.put("username", it) }
                    password?.let { if (!obj.has("password")) obj.put("password", it) }
                }
                obj.toString()
            } catch (_: Exception) {
                rawConfigJson
            }
        } else {
            val obj = JSONObject().apply {
                put("protocol", protocol.name)
                put("server", serverAddr)
                put("port", serverP)
                if (protocol == VpnProtocol.AMNEZIA_WG) {
                    put("jc", 4)
                    put("jmin", 40)
                    put("jmax", 70)
                    put("s1", 28)
                    put("s2", 44)
                    put("h1", 1)
                    put("h2", 2)
                    put("h3", 3)
                    put("h4", 4)
                    if (!effectiveConfigText.isNullOrEmpty()) {
                        put("config_text", effectiveConfigText)
                    }
                } else if (protocol == VpnProtocol.OPENCONNECT) {
                    username?.let { put("username", it) }
                    password?.let { put("password", it) }
                    val effectiveSecret = secret ?: group ?: "split"
                    put("secret", effectiveSecret)
                    put("secretKey", effectiveSecret)
                    put("group", group ?: effectiveSecret)
                }
            }
            obj.toString()
        }

        return TunnelConfig(
            id = id,
            serverName = "$flagEmoji $name ($location)",
            protocol = protocol,
            serverAddress = serverAddr,
            serverPort = serverP,
            ipv4Address = ipv4,
            ipv4Prefix = prefix,
            dnsServers = effectiveDns,
            routes = listOf(CidrRoute.DEFAULT_IPV4),
            rawConfigJson = effectiveRawJson
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("location", location)
            put("countryCode", countryCode)
            put("flagEmoji", flagEmoji)
            put("protocol", protocol.name)
            put("serverAddress", serverAddress)
            put("serverPort", serverPort)
            put("pingMs", pingMs)
            put("loadPercent", loadPercent)
            put("isRecommended", isRecommended)
            put("dnsServers", JSONArray(dnsServers))
            configText?.let { put("configText", it) }
            username?.let { put("username", it) }
            password?.let { put("password", it) }
            group?.let { put("group", it) }
            secret?.let { put("secret", it) }
            put("isCustom", isCustom)
            put("isFromSubscription", isFromSubscription)
            rawConfigJson?.let { put("rawConfigJson", it) }
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): ServerProfile {
            val dnsList = mutableListOf<String>()
            val dnsArr = obj.optJSONArray("dnsServers")
            if (dnsArr != null) {
                for (i in 0 until dnsArr.length()) {
                    dnsList.add(dnsArr.getString(i))
                }
            }

            val protoStr = obj.optString("protocol", VpnProtocol.AMNEZIA_WG.name)
            val protocol = try {
                VpnProtocol.valueOf(protoStr)
            } catch (e: Exception) {
                VpnProtocol.AMNEZIA_WG
            }

            return ServerProfile(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                name = obj.optString("name", "Пользовательский шлюз"),
                location = obj.optString("location", "Европа"),
                countryCode = obj.optString("countryCode", "EU"),
                flagEmoji = obj.optString("flagEmoji", "🌐"),
                protocol = protocol,
                serverAddress = obj.optString("serverAddress", "127.0.0.1"),
                serverPort = obj.optInt("serverPort", 51820),
                pingMs = obj.optInt("pingMs", 30),
                loadPercent = obj.optInt("loadPercent", 30),
                isRecommended = obj.optBoolean("isRecommended", false),
                dnsServers = dnsList,
                configText = if (obj.has("configText") && !obj.isNull("configText")) obj.getString("configText") else null,
                username = if (obj.has("username") && !obj.isNull("username")) obj.getString("username") else null,
                password = if (obj.has("password") && !obj.isNull("password")) obj.getString("password") else null,
                group = if (obj.has("group") && !obj.isNull("group")) obj.getString("group") else null,
                secret = if (obj.has("secret") && !obj.isNull("secret")) obj.getString("secret") else null,
                isCustom = obj.optBoolean("isCustom", false),
                isFromSubscription = obj.optBoolean("isFromSubscription", false),
                rawConfigJson = if (obj.has("rawConfigJson") && !obj.isNull("rawConfigJson")) obj.getString("rawConfigJson") else null
            )
        }

        val DEFAULT_SERVERS: List<ServerProfile> = emptyList()
    }
}
