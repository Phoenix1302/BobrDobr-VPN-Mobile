package com.bobrdobr.vpn.config

import java.io.BufferedReader
import java.io.StringReader

/**
 * Validation result containing parsed details or error messages.
 */
sealed class ValidationResult {
    data class SuccessAwg(
        val endpoint: String,
        val serverAddress: String,
        val serverPort: Int,
        val clientAddress: String,
        val dnsServers: List<String>,
        val hasObfuscation: Boolean,
        val obfuscationParams: Map<String, String>
    ) : ValidationResult()

    data class SuccessOcserv(
        val host: String,
        val port: Int,
        val username: String,
        val group: String,
        val secret: String = ""
    ) : ValidationResult()

    data class Error(val message: String) : ValidationResult()
}

/**
 * ConfigValidator: Validates and extracts parameters from AmneziaWG and ocserv configs.
 */
object ConfigValidator {

    /**
     * Validates WireGuard / AmneziaWG configuration text.
     */
    fun validateAwg(confText: String): ValidationResult {
        val trimmed = confText.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Error("Конфигурация пуста")
        }

        var hasInterface = false
        var hasPeer = false
        var privateKey: String? = null
        var clientAddress: String? = null
        var endpoint: String? = null
        var publicKey: String? = null
        val dnsList = mutableListOf<String>()
        val obfParams = mutableMapOf<String, String>()

        var currentSection = ""
        val reader = BufferedReader(StringReader(trimmed))
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim().orEmpty()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue

            if (l.startsWith("[") && l.endsWith("]")) {
                currentSection = l.substring(1, l.length - 1).trim().lowercase()
                if (currentSection == "interface") hasInterface = true
                if (currentSection == "peer") hasPeer = true
                continue
            }

            val parts = l.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when (currentSection) {
                "interface" -> {
                    when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> {
                            val addrs = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            clientAddress = addrs.firstOrNull() ?: value
                        }
                        "dns" -> {
                            // Can be comma or space separated: 10.8.0.1, 1.1.1.1
                            val servers = value.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
                            dnsList.addAll(servers)
                        }
                        "jc" -> obfParams["Jc"] = value.toLongOrNull()?.toString() ?: "4"
                        "jmin" -> obfParams["Jmin"] = value.toLongOrNull()?.toString() ?: "40"
                        "jmax" -> obfParams["Jmax"] = value.toLongOrNull()?.toString() ?: "70"
                        "s1" -> obfParams["S1"] = value.toLongOrNull()?.toString() ?: "28"
                        "s2" -> obfParams["S2"] = value.toLongOrNull()?.toString() ?: "44"
                        "h1" -> obfParams["H1"] = value.toLongOrNull()?.toString() ?: "1"
                        "h2" -> obfParams["H2"] = value.toLongOrNull()?.toString() ?: "2"
                        "h3" -> obfParams["H3"] = value.toLongOrNull()?.toString() ?: "3"
                        "h4" -> obfParams["H4"] = value.toLongOrNull()?.toString() ?: "4"
                    }
                }
                "peer" -> {
                    when (key) {
                        "publickey" -> publicKey = value
                        "endpoint" -> endpoint = value
                    }
                }
            }
        }

        if (!hasInterface) {
            return ValidationResult.Error("Отсутствует секция [Interface]")
        }
        if (privateKey.isNullOrBlank()) {
            return ValidationResult.Error("В секции [Interface] отсутствует PrivateKey")
        }
        if (!hasPeer) {
            return ValidationResult.Error("Отсутствует секция [Peer]")
        }
        if (publicKey.isNullOrBlank()) {
            return ValidationResult.Error("В секции [Peer] отсутствует PublicKey")
        }
        if (endpoint.isNullOrBlank()) {
            return ValidationResult.Error("В секции [Peer] отсутствует Endpoint")
        }

        // Parse host & port (supporting IPv6 [2001:db8::1]:51820 as well as IPv4/domain host:port)
        val (serverAddress, serverPort) = if (endpoint.startsWith("[")) {
            val closeBracket = endpoint.indexOf("]")
            if (closeBracket != -1) {
                val host = endpoint.substring(1, closeBracket).trim()
                val portPart = endpoint.substring(closeBracket + 1).removePrefix(":")
                val port = portPart.toIntOrNull() ?: 51820
                Pair(host, port)
            } else {
                val epParts = endpoint.split(":")
                Pair(epParts[0].trim(), epParts.getOrNull(1)?.toIntOrNull() ?: 51820)
            }
        } else if (endpoint.count { it == ':' } > 1) {
            // Raw IPv6 address without brackets
            Pair(endpoint.trim(), 51820)
        } else {
            val epParts = endpoint.split(":")
            Pair(epParts[0].trim(), epParts.getOrNull(1)?.toIntOrNull() ?: 51820)
        }

        return ValidationResult.SuccessAwg(
            endpoint = endpoint,
            serverAddress = serverAddress,
            serverPort = serverPort,
            clientAddress = clientAddress ?: "10.8.0.2/32",
            dnsServers = dnsList,
            hasObfuscation = obfParams.isNotEmpty(),
            obfuscationParams = obfParams
        )
    }

    /**
     * Validates OpenConnect / ocserv credentials.
     */
    fun validateOcserv(
        host: String,
        port: Int,
        username: String,
        password: String,
        group: String = "",
        secret: String = ""
    ): ValidationResult {
        val cleanHost = host.trim().replace("https://", "").replace("http://", "").split("/")[0]
        if (cleanHost.isEmpty()) {
            return ValidationResult.Error("Адрес сервера не может быть пустым")
        }
        if (port !in 1..65535) {
            return ValidationResult.Error("Порт должен быть в диапазоне от 1 до 65535")
        }
        if (username.trim().isEmpty()) {
            return ValidationResult.Error("Имя пользователя не может быть пустым")
        }
        if (password.isEmpty()) {
            return ValidationResult.Error("Пароль не может быть пустым")
        }

        return ValidationResult.SuccessOcserv(
            host = cleanHost,
            port = port,
            username = username.trim(),
            group = group.trim(),
            secret = secret.trim()
        )
    }
}
