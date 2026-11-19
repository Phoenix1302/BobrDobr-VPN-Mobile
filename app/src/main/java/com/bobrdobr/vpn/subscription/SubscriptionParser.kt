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

            // Extract camouflage secret from camouflage object, URI query (e.g. ?kurwa or ?secret=...), or direct secret field
            var secret = obj.optJSONObject("camouflage")?.optString("secret", "") ?: ""
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
                secret = obj.optString("secret", obj.optString("secretKey", group))
            }
            if (secret.isEmpty()) {
                secret = group
            }

            var host = defaultHost
            var port = 443

            if (uriStr.isNotEmpty()) {
                try {
                    val uri = URI(uriStr)
                    if (!uri.host.isNullOrEmpty()) host = uri.host
                    if (uri.port > 0) port = uri.port
                } catch (_: Exception) {}
            }
            if (host.isEmpty()) {
                host = obj.optString("host", obj.optString("server", "127.0.0.1"))
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
            var configText = obj.optString("config_text", "")
            if (configText.isBlank()) configText = obj.optString("config_content", "")
            if (configText.isBlank()) configText = obj.optString("config", "")
            if (configText.isBlank()) configText = obj.optString("configText", "")
            if (configText.isBlank()) configText = obj.optString("raw_config", "")

            var serverAddr = defaultHost
            var serverPort = 51820
            val dnsList = mutableListOf<String>()

            // If configText is blank, attempt to construct AmneziaWG INI from individual JSON fields
            val privKey = obj.optString("private_key", obj.optString("privateKey", ""))
            val pubKey = obj.optString("public_key", obj.optString("publicKey", ""))
            if (configText.isBlank() && privKey.isNotBlank()) {
                val addr = obj.optString("address", obj.optString("ip", "10.8.0.2/32"))
                val ep = obj.optString("endpoint", if (serverAddr.isNotBlank()) "$serverAddr:$serverPort" else "")
                val psk = obj.optString("preshared_key", obj.optString("presharedKey", ""))
                val dnsVal = obj.optString("dns", "")
                val sb = StringBuilder()
                sb.append("[Interface]\n")
                sb.append("Address = $addr\n")
                sb.append("PrivateKey = $privKey\n")
                if (dnsVal.isNotBlank()) sb.append("DNS = $dnsVal\n")
                if (obj.has("jc")) sb.append("Jc = ${obj.optInt("jc")}\n")
                if (obj.has("jmin")) sb.append("Jmin = ${obj.optInt("jmin")}\n")
                if (obj.has("jmax")) sb.append("Jmax = ${obj.optInt("jmax")}\n")
                if (obj.has("s1")) sb.append("S1 = ${obj.optInt("s1")}\n")
                if (obj.has("s2")) sb.append("S2 = ${obj.optInt("s2")}\n")
                if (obj.has("h1")) sb.append("H1 = ${obj.optInt("h1")}\n")
                if (obj.has("h2")) sb.append("H2 = ${obj.optInt("h2")}\n")
                if (obj.has("h3")) sb.append("H3 = ${obj.optInt("h3")}\n")
                if (obj.has("h4")) sb.append("H4 = ${obj.optInt("h4")}\n")
                sb.append("\n[Peer]\n")
                if (pubKey.isNotBlank()) sb.append("PublicKey = $pubKey\n")
                if (ep.isNotBlank()) sb.append("Endpoint = $ep\n")
                if (psk.isNotBlank()) sb.append("PresharedKey = $psk\n")
                sb.append("AllowedIPs = 0.0.0.0/0\n")
                configText = sb.toString()
            }

            if (configText.isNotBlank()) {
                val validation = ConfigValidator.validateAwg(configText)
                if (validation is ValidationResult.SuccessAwg) {
                    serverAddr = validation.serverAddress
                    serverPort = validation.serverPort
                    dnsList.addAll(validation.dnsServers)
                }
            }

            val endpoint = obj.optString("endpoint", "")
            if (endpoint.isNotEmpty() && endpoint.contains(":")) {
                val epParts = endpoint.split(":")
                serverAddr = epParts[0].trim()
                serverPort = epParts[1].toIntOrNull() ?: 51820
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
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                val d = sdf.parse(dateStr)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun createSingleProfileSubscription(profile: SubscriptionProfile, sourceUrl: String): SubscriptionInfo {
        return SubscriptionInfo(
            hasSubscription = true,
            url = sourceUrl,
            cabinetUrl = deriveCabinetUrl(sourceUrl),
            userId = "imported-key",
            username = "Egor",
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
