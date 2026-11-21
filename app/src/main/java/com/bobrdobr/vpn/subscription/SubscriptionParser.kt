package com.bobrdobr.vpn.subscription

import android.util.Log
import com.bobrdobr.vpn.config.ConfigValidator
import com.bobrdobr.vpn.config.ValidationResult
import org.bobrdobr.vpn.core.model.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale

/**
 * Universal subscription payload parser for BobrDobr VPN.
 *
 * Supports:
 * 1. Persona / BobrDobr subscription JSON schema:
 *    - user (username, role, expires_at: ISO 8601 string or epoch)
 *    - server (host, app_version)
 *    - connections (awg with config_text, ocserv with credentials and uri)
 * 2. Dynamic Cabinet URL generation (location-domain:/cloud).
 * 3. Base64-encoded server lists and raw .conf format.
 */
object SubscriptionParser {

    private const val TAG = "SubscriptionParser"

    fun parse(payload: String, sourceUrl: String = ""): SubscriptionInfo {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Subscription payload cannot be empty")
        }

        // Try direct JSON parsing
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseSubscriptionJson(trimmed, sourceUrl)
        }

        // Try Base64 decoding
        val decoded = tryDecodeBase64(trimmed)
        if (decoded != null) {
            val decTrimmed = decoded.trim()
            if (decTrimmed.startsWith("{") && decTrimmed.endsWith("}")) {
                return parseSubscriptionJson(decTrimmed, sourceUrl)
            } else if (decTrimmed.contains("[Interface]", ignoreCase = true)) {
                val profile = parseRawAwgConfig(decTrimmed, "Импортированная конфигурация")
                return createSingleProfileSubscription(profile, sourceUrl)
            }
        }

        // Try raw AWG .conf directly
        if (trimmed.contains("[Interface]", ignoreCase = true)) {
            val profile = parseRawAwgConfig(trimmed, "Импортированная конфигурация")
            return createSingleProfileSubscription(profile, sourceUrl)
        }

        throw IllegalArgumentException("Неподдерживаемый формат подписки: ожидается JSON или Base64")
    }

    private fun parseSubscriptionJson(jsonStr: String, sourceUrl: String): SubscriptionInfo {
        val root = JSONObject(jsonStr)

        val rawStatus = root.optString("status", "active")
        var status = SubscriptionStatus.fromString(rawStatus)
        var isFrozen = root.optInt("is_frozen", 0)

        // Dynamic Cabinet URL derivation: https://<domain>/cloud
        val dynamicCabinetUrl = deriveCabinetUrl(sourceUrl).ifEmpty {
            root.optString("cabinet_url", root.optString("dashboard_url", ""))
        }

        var userId = ""
        var username = "Egor"
        var role = "user"
        var expiresAt = 0L

        if (root.has("user") && !root.isNull("user")) {
            val userObj = root.getJSONObject("user")
            userId = userObj.optString("id", "")
            username = userObj.optString("username", "Egor")
            role = userObj.optString("role", "user")

            val rawExpires = userObj.opt("expires_at")
            expiresAt = when (rawExpires) {
                is Number -> {
                    val exp = rawExpires.toLong()
                    if (exp < 100_000_000_000L) exp * 1000L else exp
                }
                is String -> {
                    // Try parsing ISO 8601 string or numeric string
                    parseIso8601(rawExpires).takeIf { it > 0 }
                        ?: (rawExpires.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000L else it } ?: 0L)
                }
                else -> 0L
            }

            if (userObj.has("is_frozen")) {
                isFrozen = userObj.optInt("is_frozen", isFrozen)
            }
        }

        var serverHost = ""
        if (root.has("server") && !root.isNull("server")) {
            val srvObj = root.getJSONObject("server")
            serverHost = srvObj.optString("host", "")
        }

        // Check is_frozen flag rules: 0 = Active, 1 = Expired, 2 = Blocked
        when (isFrozen) {
            1 -> status = SubscriptionStatus.EXPIRED
            2 -> status = SubscriptionStatus.BLOCKED
        }

        val daysLeft = if (expiresAt > 0) {
            val diff = expiresAt - System.currentTimeMillis()
            (diff / (1000L * 60L * 60L * 24L)).coerceAtLeast(0L).toInt()
        } else {
            365
        }

        val profilesList = mutableListOf<SubscriptionProfile>()

        // Look for connections, servers, or profiles array
        val connsArray = root.optJSONArray("connections")
            ?: root.optJSONArray("servers")
            ?: root.optJSONArray("profiles")

        if (connsArray != null) {
            for (i in 0 until connsArray.length()) {
                val connObj = connsArray.optJSONObject(i) ?: continue
                try {
                    val profile = parseConnectionObject(connObj, i, serverHost)
                    profilesList.add(profile)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse connection item $i", e)
                }
            }
        }

        return SubscriptionInfo(
            hasSubscription = sourceUrl.isNotBlank() || profilesList.isNotEmpty(),
            url = sourceUrl,
            cabinetUrl = dynamicCabinetUrl,
            userId = userId,
            username = username,
            role = role,
            serverHost = serverHost,
            status = status,
            expiresAt = expiresAt,
            daysLeft = daysLeft,
            isFrozen = isFrozen,
            isActive = (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.EXPIRING) && isFrozen == 0,
            profiles = profilesList,
            lastSyncTime = System.currentTimeMillis()
        )
    }

    private fun parseConnectionObject(obj: JSONObject, index: Int, defaultHost: String): SubscriptionProfile {
        val id = obj.optString("id", "conn-$index")
        val rawName = obj.optString("name", "Шлюз #$index")
        val name = formatProfileName(rawName)
        val protoStr = obj.optString("proto", obj.optString("protocol", "awg")).lowercase()

        val isOcserv = protoStr.contains("openconnect") || protoStr.contains("ocserv") || protoStr.contains("anyconnect")

        if (isOcserv) {
            // ocserv AnyConnect profile
            val uriStr = obj.optString("uri", "")
            val username = obj.optString("username", "")
            val password = obj.optString("password", "")
            val group = obj.optString("group", "split")

            // Extract camouflage secret from camouflage object, URI query (e.g. ?secret=...), or direct secret field
            val camoObj = obj.optJSONObject("camouflage")
            var secret = camoObj?.optString("secret", "") ?: ""
            if (secret.isEmpty() && camoObj != null) {
                val q = camoObj.optString("query", "").trimStart('?')
                if (q.isNotEmpty()) secret = q
                if (secret.isEmpty()) {
                    val p = camoObj.optString("path", "").trimStart('/', '?')
                    if (p.isNotEmpty()) secret = p
                }
            }
            if (secret.isEmpty() && uriStr.isNotEmpty()) {
                try {
                    val uri = URI(uriStr)
                    val query = uri.rawQuery ?: ""
                    if (query.isNotEmpty()) {
                        val params = query.split("&")
                        for (p in params) {
                            val kv = p.split("=", limit = 2)
                            if (kv.size == 2 && (kv[0].equals("secret", ignoreCase = true) || kv[0].equals("secretKey", ignoreCase = true))) {
                                secret = kv[1]
                                break
                            } else if (kv.size == 1 && kv[0].isNotBlank()) {
                                val key = kv[0]
                                if (!key.startsWith("user", ignoreCase = true) &&
                                    !key.startsWith("pass", ignoreCase = true) &&
                                    !key.startsWith("group", ignoreCase = true)) {
                                    secret = key
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            if (secret.isEmpty()) {
                secret = obj.optString("secret", obj.optString("secretKey", ""))
            }

            var host = defaultHost
            var port = 443
            if (obj.has("host") && obj.getString("host").isNotBlank()) {
                host = obj.getString("host")
            } else if (obj.has("server") && obj.getString("server").isNotBlank()) {
                host = obj.getString("server")
            }
            if (obj.has("port") && obj.optInt("port", 0) > 0) {
                port = obj.getInt("port")
            } else if (obj.has("serverPort") && obj.optInt("serverPort", 0) > 0) {
                port = obj.getInt("serverPort")
            }

            if (uriStr.isNotEmpty()) {
                try {
                    val uri = URI(uriStr)
                    if (!uri.host.isNullOrEmpty()) host = uri.host
                    if (uri.port > 0) port = uri.port
                } catch (_: Exception) {}
            }
            if (host.isEmpty()) {
                host = "127.0.0.1"
            }

            val (location, countryCode, flagEmoji) = detectLocation(name)

            return SubscriptionProfile(
                id = id,
                name = name,
                protocol = VpnProtocol.OPENCONNECT,
                serverAddress = host,
                serverPort = port,
                location = location,
                countryCode = countryCode,
                flagEmoji = flagEmoji,
                pingMs = obj.optInt("ping", 0),
                loadPercent = obj.optInt("load", 0),
                isRecommended = index == 0,
                username = username,
                password = password,
                group = group,
                secret = secret,
                rawConfigJson = obj.toString()
            )
        } else {
            // AmneziaWG profile
            var rawConfig = obj.optString("config_text", "")
            if (rawConfig.isBlank()) rawConfig = obj.optString("config_content", "")
            if (rawConfig.isBlank()) rawConfig = obj.optString("config", "")
            if (rawConfig.isBlank()) rawConfig = obj.optString("configText", "")
            if (rawConfig.isBlank()) rawConfig = obj.optString("raw_config", "")

            val endpoint = obj.optString("endpoint", "")
            val ip = obj.optString("ip", obj.optString("address", ""))
            val ipv6 = obj.optString("ipv6", "")
            val dns = obj.optString("dns", "")
            val allowedIps = obj.optString("allowed_ips", obj.optString("allowedIps", ""))
            val obfObj = obj.optJSONObject("obfuscation")

            val configText = enrichAwgConfig(rawConfig, endpoint, ip, ipv6, dns, allowedIps, obfObj, obj, defaultHost)

            var serverAddr = defaultHost
            var serverPort = 51820
            val dnsList = mutableListOf<String>()

            if (dns.isNotBlank()) {
                dns.split(",").forEach { d ->
                    val trimmedD = d.trim()
                    if (trimmedD.isNotEmpty() && !dnsList.contains(trimmedD)) {
                        dnsList.add(trimmedD)
                    }
                }
            }

            if (configText.isNotBlank()) {
                val validation = ConfigValidator.validateAwg(configText)
                if (validation is ValidationResult.SuccessAwg) {
                    if (validation.serverAddress.isNotBlank()) {
                        serverAddr = validation.serverAddress
                        serverPort = validation.serverPort
                    }
                    validation.dnsServers.forEach { d ->
                        if (!dnsList.contains(d)) dnsList.add(d)
                    }
                }
            }

            if (endpoint.isNotEmpty() && endpoint.contains(":")) {
                val epParts = endpoint.split(":")
                serverAddr = epParts[0].trim()
                serverPort = epParts[1].toIntOrNull() ?: serverPort
            } else if (serverAddr.isEmpty()) {
                serverAddr = obj.optString("host", obj.optString("server", "127.0.0.1"))
                serverPort = obj.optInt("port", 51820)
            }

            val (location, countryCode, flagEmoji) = detectLocation(name)

            return SubscriptionProfile(
                id = id,
                name = name,
                protocol = VpnProtocol.AMNEZIA_WG,
                serverAddress = serverAddr,
                serverPort = serverPort,
                location = location,
                countryCode = countryCode,
                flagEmoji = flagEmoji,
                pingMs = obj.optInt("ping", 0),
                loadPercent = obj.optInt("load", 0),
                isRecommended = index == 0,
                dnsServers = dnsList,
                configText = configText,
                rawConfigJson = obj.toString()
            )
        }
    }

    /**
     * Enriches AmneziaWG configuration with missing obfuscation params, Endpoint, Address, and DNS.
     */
    fun enrichAwgConfig(
        rawConfig: String,
        endpoint: String,
        ip: String,
        ipv6: String,
        dns: String,
        allowedIps: String,
        obfObj: JSONObject?,
        obj: JSONObject,
        defaultHost: String
    ): String {
        val jc = obfObj?.optInt("Jc", obfObj.optInt("jc", -1)) ?: obj.optInt("jc", obj.optInt("Jc", -1))
        val jmin = obfObj?.optInt("Jmin", obfObj.optInt("jmin", -1)) ?: obj.optInt("jmin", obj.optInt("Jmin", -1))
        val jmax = obfObj?.optInt("Jmax", obfObj.optInt("jmax", -1)) ?: obj.optInt("jmax", obj.optInt("Jmax", -1))
        val s1 = obfObj?.optInt("S1", obfObj.optInt("s1", -1)) ?: obj.optInt("s1", obj.optInt("S1", -1))
        val s2 = obfObj?.optInt("S2", obfObj.optInt("s2", -1)) ?: obj.optInt("s2", obj.optInt("S2", -1))
        val h1 = obfObj?.optLong("H1", obfObj.optLong("h1", -1L)) ?: obj.optLong("h1", obj.optLong("H1", -1L))
        val h2 = obfObj?.optLong("H2", obfObj.optLong("h2", -1L)) ?: obj.optLong("h2", obj.optLong("H2", -1L))
        val h3 = obfObj?.optLong("H3", obfObj.optLong("h3", -1L)) ?: obj.optLong("h3", obj.optLong("H3", -1L))
        val h4 = obfObj?.optLong("H4", obfObj.optLong("h4", -1L)) ?: obj.optLong("h4", obj.optLong("H4", -1L))

        val privKey = obj.optString("private_key",
            obj.optString("privateKey",
            obj.optString("client_private_key",
            obj.optString("clientPrivateKey", ""))))

        var pubKey = obj.optString("public_key",
            obj.optString("publicKey",
            obj.optString("server_public_key",
            obj.optString("serverPublicKey",
            obj.optString("peer_public_key",
            obj.optString("peerPublicKey",
            obj.optString("peer_key",
            obj.optString("peerKey", ""))))))))

        if (pubKey.isBlank()) {
            val peerObj = obj.optJSONObject("peer") ?: obj.optJSONObject("server")
            if (peerObj != null) {
                pubKey = peerObj.optString("public_key",
                    peerObj.optString("publicKey",
                    peerObj.optString("server_public_key",
                    peerObj.optString("serverPublicKey", ""))))
            }
        }

        var ep = if (endpoint.isNotBlank()) endpoint else obj.optString("endpoint", "")
        if (ep.isBlank()) {
            val srvHost = obj.optString("server", obj.optString("host", defaultHost))
            val srvPort = obj.optInt("port", obj.optInt("serverPort", 51820))
            if (srvHost.isNotBlank()) {
                ep = if (srvHost.contains(":")) srvHost else "$srvHost:$srvPort"
            }
        }

        if (rawConfig.isBlank()) {
            if (privKey.isBlank()) return ""
            val addr = if (ip.isNotBlank()) {
                if (ipv6.isNotBlank()) "$ip/32, $ipv6/128" else "$ip/32"
            } else "10.8.0.2/32"
            val psk = obj.optString("preshared_key", obj.optString("presharedKey", ""))
            val aIps = if (allowedIps.isNotBlank()) allowedIps else "0.0.0.0/0, ::/0"

            val sb = StringBuilder()
            sb.append("[Interface]\n")
            sb.append("Address = $addr\n")
            sb.append("PrivateKey = $privKey\n")
            if (dns.isNotBlank()) sb.append("DNS = $dns\n")
            if (jc >= 0) sb.append("Jc = $jc\n")
            if (jmin >= 0) sb.append("Jmin = $jmin\n")
            if (jmax >= 0) sb.append("Jmax = $jmax\n")
            if (s1 >= 0) sb.append("S1 = $s1\n")
            if (s2 >= 0) sb.append("S2 = $s2\n")
            if (h1 >= 0) sb.append("H1 = $h1\n")
            if (h2 >= 0) sb.append("H2 = $h2\n")
            if (h3 >= 0) sb.append("H3 = $h3\n")
            if (h4 >= 0) sb.append("H4 = $h4\n")

            sb.append("\n[Peer]\n")
            if (pubKey.isNotBlank()) sb.append("PublicKey = $pubKey\n")
            if (ep.isNotBlank()) sb.append("Endpoint = $ep\n")
            if (psk.isNotBlank()) sb.append("PresharedKey = $psk\n")
            sb.append("AllowedIPs = $aIps\n")
            return sb.toString()
        }

        var config = rawConfig

        // 1. Check obfuscation in [Interface]
        if (!config.contains(Regex("(?im)^\\s*Jc\\s*=")) && jc >= 0) {
            val obfLines = buildString {
                append("Jc = $jc\n")
                if (jmin >= 0) append("Jmin = $jmin\n")
                if (jmax >= 0) append("Jmax = $jmax\n")
                if (s1 >= 0) append("S1 = $s1\n")
                if (s2 >= 0) append("S2 = $s2\n")
                if (h1 >= 0) append("H1 = $h1\n")
                if (h2 >= 0) append("H2 = $h2\n")
                if (h3 >= 0) append("H3 = $h3\n")
                if (h4 >= 0) append("H4 = $h4\n")
            }
            config = config.replaceFirst(Regex("(?i)(\\[Interface\\]\\r?\\n)"), "$1$obfLines")
        }

        // 2. Check DNS in [Interface]
        if (!config.contains(Regex("(?im)^\\s*DNS\\s*=")) && dns.isNotBlank()) {
            config = config.replaceFirst(Regex("(?i)(\\[Interface\\]\\r?\\n)"), "$1DNS = $dns\n")
        }

        // 3. Check Address in [Interface]
        if (!config.contains(Regex("(?im)^\\s*Address\\s*=")) && ip.isNotBlank()) {
            val addrStr = if (ipv6.isNotBlank()) "$ip/32, $ipv6/128" else "$ip/32"
            config = config.replaceFirst(Regex("(?i)(\\[Interface\\]\\r?\\n)"), "$1Address = $addrStr\n")
        }

        // 4. Check [Peer]
        if (config.contains(Regex("(?i)\\[Peer\\]"))) {
            if (!config.contains(Regex("(?im)^\\s*PublicKey\\s*=")) && pubKey.isNotBlank()) {
                config = config.replaceFirst(Regex("(?i)(\\[Peer\\]\\r?\\n)"), "$1PublicKey = $pubKey\n")
            }
            if (!config.contains(Regex("(?im)^\\s*Endpoint\\s*=")) && ep.isNotBlank()) {
                config = config.replaceFirst(Regex("(?i)(\\[Peer\\]\\r?\\n)"), "$1Endpoint = $ep\n")
            }
            if (!config.contains(Regex("(?im)^\\s*AllowedIPs\\s*=")) && allowedIps.isNotBlank()) {
                config = config.replaceFirst(Regex("(?i)(\\[Peer\\]\\r?\\n)"), "$1AllowedIPs = $allowedIps\n")
            }
        } else {
            val peerBlock = buildString {
                append("\n[Peer]\n")
                if (pubKey.isNotBlank()) append("PublicKey = $pubKey\n")
                if (ep.isNotBlank()) append("Endpoint = $ep\n")
                val psk = obj.optString("preshared_key", obj.optString("presharedKey", ""))
                if (psk.isNotBlank()) append("PresharedKey = $psk\n")
                val aIps = if (allowedIps.isNotBlank()) allowedIps else "0.0.0.0/0, ::/0"
                append("AllowedIPs = $aIps\n")
            }
            config += peerBlock
        }

        // Explicit values from the subscription object are authoritative over stale
        // values embedded in config_text. This keeps the generated profile aligned
        // with the server response schema.
        val explicitObfuscation: List<Pair<String, Long>> = listOf(
            "Jc" to jc.toLong(), "Jmin" to jmin.toLong(), "Jmax" to jmax.toLong(),
            "S1" to s1.toLong(), "S2" to s2.toLong(), "H1" to h1, "H2" to h2,
            "H3" to h3, "H4" to h4
        ).filter { it.second >= 0L }
        for ((key, value) in explicitObfuscation) {
            val line = Regex("(?im)^\\s*${key}\\s*=.*$")
            config = if (line.containsMatchIn(config)) {
                line.replace(config, "$key = $value")
            } else {
                config.replaceFirst(Regex("(?i)(\\[Interface\\]\\r?\\n)"), "$1$key = $value\\n")
            }
        }

        return config
    }

    /**
     * Parses standard or obfuscated WireGuard / AmneziaWG text format.
     */
    fun parseRawAwgConfig(content: String, defaultName: String): SubscriptionProfile {
        val validation = ConfigValidator.validateAwg(content)
        val dnsList = if (validation is ValidationResult.SuccessAwg) validation.dnsServers else emptyList()
        val serverAddr = if (validation is ValidationResult.SuccessAwg) validation.serverAddress else "127.0.0.1"
        val serverPort = if (validation is ValidationResult.SuccessAwg) validation.serverPort else 51820

        val (location, countryCode, flagEmoji) = detectLocation(defaultName)

        return SubscriptionProfile(
            id = "awg-import-${System.currentTimeMillis() % 10000}",
            name = defaultName,
            protocol = VpnProtocol.AMNEZIA_WG,
            serverAddress = serverAddr,
            serverPort = serverPort,
            location = location,
            countryCode = countryCode,
            flagEmoji = flagEmoji,
            dnsServers = dnsList,
            configText = content,
            rawConfigJson = JSONObject().put("config_text", content).toString()
        )
    }

    /**
     * Derives dynamic cabinet URL: https://<domain>/cloud from subscription source URL.
     */
    fun deriveCabinetUrl(sourceUrl: String): String {
        return try {
            val uri = URI(sourceUrl.trim())
            val scheme = uri.scheme ?: "https"
            val host = uri.host
            if (!host.isNullOrEmpty()) {
                val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
                "$scheme://$host$port/cloud"
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseIso8601(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        try {
            return java.time.Instant.parse(dateStr).toEpochMilli()
        } catch (_: Exception) {}

        try {
            return java.time.OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
        } catch (_: Exception) {}

        try {
            return java.time.LocalDateTime.parse(dateStr).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {}

        try {
            val normalized = dateStr.trim().replace(" ", "T")
            return java.time.LocalDateTime.parse(normalized).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {}

        return 0L
    }

    private fun createSingleProfileSubscription(profile: SubscriptionProfile, sourceUrl: String): SubscriptionInfo {
        return SubscriptionInfo(
            hasSubscription = true,
            url = sourceUrl,
            cabinetUrl = deriveCabinetUrl(sourceUrl),
            userId = "imported-key",
            username = "Пользователь",
            role = "user",
            status = SubscriptionStatus.ACTIVE,
            expiresAt = System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L,
            daysLeft = 365,
            isFrozen = 0,
            isActive = true,
            profiles = listOf(profile),
            lastSyncTime = System.currentTimeMillis()
        )
    }

    private fun tryDecodeBase64(str: String): String? {
        return try {
            val cleanStr = str.replace("\n", "").replace("\r", "").trim()
            val bytes = Base64.getDecoder().decode(cleanStr)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                val bytes = Base64.getUrlDecoder().decode(str.trim())
                String(bytes, Charsets.UTF_8)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun formatProfileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Бобр-Шлюз"
        return trimmed.replace(Regex("(?i)(\\s*-\\s*sub)+$"), "").trim()
    }

    private fun detectLocation(name: String): Triple<String, String, String> {
        val lower = name.lowercase()
        return when {
            lower.contains("de") || lower.contains("герман") || lower.contains("frankfurt") ->
                Triple("Frankfurt am Main", "DE", "🇩🇪")
            lower.contains("nl") || lower.contains("нидерланд") || lower.contains("amsterdam") ->
                Triple("Amsterdam", "NL", "🇳🇱")
            lower.contains("fi") || lower.contains("финлянд") || lower.contains("helsinki") ->
                Triple("Helsinki", "FI", "🇫🇮")
            lower.contains("se") || lower.contains("швеци") || lower.contains("stockholm") ->
                Triple("Stockholm", "SE", "🇸🇪")
            lower.contains("ch") || lower.contains("швейцар") || lower.contains("zurich") ->
                Triple("Zurich", "CH", "🇨🇭")
            lower.contains("us") || lower.contains("сша") || lower.contains("america") ->
                Triple("New York", "US", "🇺🇸")
            lower.contains("ru") || lower.contains("росси") || lower.contains("moscow") ->
                Triple("Москва", "RU", "🇷🇺")
            else ->
                Triple("Европа", "EU", "🌐")
        }
    }
}
