package org.bobrdobr.vpn.core.logger

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import org.bobrdobr.vpn.core.model.TunnelConfig
import org.bobrdobr.vpn.core.service.VpnTunnelService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DiagnosticDumpManager: Gathers comprehensive device, network, VPN session,
 * and execution logs into a unified UTF-8 diagnostic report with one-tap system sharing.
 */
object DiagnosticDumpManager {

    private const val TAG = "DiagnosticDumpManager"

    fun getAppVersionName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } ?: "0.8.11-prerelease"
        } catch (_: Exception) {
            "0.8.11-prerelease"
        }
    }

    fun getAppVersionCode(context: Context): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) {
            84L
        }
    }

    /**
     * Generates a complete UTF-8 diagnostic dump string, saves it to the app's cache directory,
     * and returns the generated [File].
     */
    fun generateDumpFile(context: Context, activeConfig: TunnelConfig? = null): File {
        val dumpContent = generateDumpContent(context, activeConfig)
        val dumpsDir = File(context.cacheDir, "debug_dumps").apply {
            if (!exists()) mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dumpFile = File(dumpsDir, "BobrDobr_Debug_Dump_$timestamp.txt")
        dumpFile.writeText(dumpContent, Charsets.UTF_8)

        DebugLogger.i(TAG, "Generated diagnostic dump: ${dumpFile.absolutePath} (${dumpFile.length()} bytes)")
        return dumpFile
    }

    /**
     * Generates a diagnostic dump file and opens Android System Sharing Chooser
     * to share via Telegram, Email, or save to disk.
     */
    fun shareDump(context: Context, activeConfig: TunnelConfig? = null): Boolean {
        return try {
            val file = generateDumpFile(context, activeConfig)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val vName = getAppVersionName(context)
            val vCode = getAppVersionCode(context)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain; charset=utf-8"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "БобрДобр VPN $vName: Диагностический дамп")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Диагностический дамп БобрДобр VPN Mobile ($vName, сборка $vCode).\nФайл: ${file.name}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Отправить диагностический дамп").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to share diagnostic dump: ${e.message}", e)
            false
        }
    }

    /**
     * Formats comprehensive diagnostic data into a single human-readable UTF-8 report.
     */
    fun generateDumpContent(context: Context, activeConfig: TunnelConfig? = null): String {
        val sb = StringBuilder(32 * 1024)

        val localDf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault())
        val utcDf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = Date()

        val vName = getAppVersionName(context)
        val vCode = getAppVersionCode(context)

        sb.append("================================================================================\n")
        sb.append("               БОБРДОБР VPN (BOBRDOBR VPN) MOBILE DIAGNOSTIC DUMP               \n")
        sb.append("================================================================================\n\n")

        // 1. Header & Version
        sb.append("--- [1. REPORT METADATA] ---\n")
        sb.append("App Version      : BobrDobr VPN Mobile $vName (build $vCode)\n")
        sb.append("Package Name     : ${context.packageName}\n")
        sb.append("Local Timestamp  : ${localDf.format(now)}\n")
        sb.append("UTC Timestamp    : ${utcDf.format(now)}\n")
        sb.append("Device Uptime    : ${SystemClock.elapsedRealtime() / 1000} seconds\n\n")

        // 2. Hardware & OS Environment
        sb.append("--- [2. HARDWARE & ANDROID OS] ---\n")
        sb.append("Manufacturer     : ${Build.MANUFACTURER}\n")
        sb.append("Model            : ${Build.MODEL}\n")
        sb.append("Device / Board   : ${Build.DEVICE} / ${Build.BOARD}\n")
        sb.append("Brand / Product  : ${Build.BRAND} / ${Build.PRODUCT}\n")
        sb.append("Android Release  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Security Patch   : ${Build.VERSION.SECURITY_PATCH}\n")
        sb.append("Supported ABIs   : ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        val pageSizeStr = try {
            val ps = Os.sysconf(OsConstants._SC_PAGESIZE)
            "$ps bytes (${ps / 1024} KB) - ${if (ps == 16384L) "16KB Page-Size ALIGNED" else "4KB Standard"}"
        } catch (t: Throwable) {
            "Unknown (${t.message})"
        }
        sb.append("System Page Size : $pageSizeStr\n")
        sb.append("Kernel OS Arch   : ${System.getProperty("os.arch")} (${System.getProperty("os.name")} ${System.getProperty("os.version")})\n\n")

        // 3. JVM Memory & Storage
        sb.append("--- [3. MEMORY & STORAGE] ---\n")
        val runtime = Runtime.getRuntime()
        val mb = 1024L * 1024L
        sb.append("JVM Heap Total   : ${runtime.totalMemory() / mb} MB\n")
        sb.append("JVM Heap Free    : ${runtime.freeMemory() / mb} MB\n")
        sb.append("JVM Heap Max     : ${runtime.maxMemory() / mb} MB\n")
        sb.append("Internal Storage : ${context.cacheDir.freeSpace / mb} MB free\n\n")

        // 4. Physical Network Environment
        sb.append("--- [4. ACTIVE NETWORK ENVIRONMENT] ---\n")
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                sb.append("Active Network Handle : ${activeNet.networkHandle}\n")
                val caps = cm.getNetworkCapabilities(activeNet)
                if (caps != null) {
                    val transports = mutableListOf<String>()
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ETHERNET")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN")
                    sb.append("Transports           : ${transports.joinToString(", ")}\n")
                    sb.append("Internet Capability  : ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}\n")
                    sb.append("Validated            : ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}\n")
                    sb.append("Not VPN              : ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}\n")
                    sb.append("Link Downstream Band : ${caps.linkDownstreamBandwidthKbps} Kbps\n")
                    sb.append("Link Upstream Band   : ${caps.linkUpstreamBandwidthKbps} Kbps\n")
                }

                val lp = cm.getLinkProperties(activeNet)
                if (lp != null) {
                    sb.append("Interface Name       : ${lp.interfaceName}\n")
                    sb.append("Link Addresses       : ${lp.linkAddresses.joinToString(", ") { it.toString() }}\n")
                    sb.append("DNS Servers          : ${lp.dnsServers.joinToString(", ") { it.hostAddress ?: "" }}\n")
                    sb.append("Routes               : ${lp.routes.joinToString(" | ") { it.toString() }}\n")
                    sb.append("MTU                  : ${lp.mtu}\n")
                }
            } else {
                sb.append("No active network reported by ConnectivityManager.\n")
            }
        } catch (e: Exception) {
            sb.append("Error inspecting active network: ${e.message}\n")
        }
        sb.append("\n")

        // 5. Network Interfaces Listing
        sb.append("--- [5. NETWORK INTERFACES] ---\n")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (ni in interfaces) {
                val ips = ni.inetAddresses.toList().map { it.hostAddress }
                sb.append("• ${ni.name} (${ni.displayName}): up=${ni.isUp}, loopback=${ni.isLoopback}, p2p=${ni.isPointToPoint}, mtu=${ni.mtu}, addrs=[${ips.joinToString(", ")}]\n")
            }
        } catch (e: Exception) {
            sb.append("Error listing network interfaces: ${e.message}\n")
        }
        sb.append("\n")

        // 6. VPN Subsystem State & Config (Sanitized)
        sb.append("--- [6. VPN SUBSYSTEM STATE] ---\n")
        sb.append("VpnState             : ${VpnTunnelService.vpnState.value}\n")
        sb.append("TunnelStats          : ${VpnTunnelService.tunnelStats.value}\n")
        sb.append("UI Visible Status    : ${VpnTunnelService.isUiVisible.value}\n")
        sb.append("Debug Mode Enabled   : ${DebugLogger.isDebugModeEnabled.value}\n")

        val effectiveConfig = activeConfig ?: VpnTunnelService.currentConfig
        if (effectiveConfig != null) {
            sb.append("\nActive Gateway Configuration (SANITIZED):\n")
            sb.append("Protocol             : ${effectiveConfig.protocol.displayName}\n")
            sb.append("Server Name          : ${effectiveConfig.serverName}\n")
            sb.append("Server Address       : ${effectiveConfig.serverAddress}\n")
            sb.append("Server Port          : ${effectiveConfig.serverPort}\n")
            sb.append("Virtual IPv4         : ${effectiveConfig.ipv4Address}/${effectiveConfig.ipv4Prefix}\n")
            sb.append("Virtual IPv6         : ${effectiveConfig.ipv6Address ?: "none"}\n")
            sb.append("MTU                  : ${effectiveConfig.mtu}\n")
            sb.append("DNS Servers          : ${effectiveConfig.dnsServers.joinToString(", ")}\n")
            sb.append("Included Routes      : ${effectiveConfig.routes.joinToString(", ")}\n")
            sb.append("Allowed Apps         : ${effectiveConfig.allowedApplications.joinToString(", ")}\n")
            sb.append("Disallowed Apps      : ${effectiveConfig.disallowedApplications.joinToString(", ")}\n")
            sb.append("\nSanitized Config Details:\n")
            sb.append(sanitizeRawConfig(effectiveConfig.rawConfigJson))
            sb.append("\n")
        } else {
            sb.append("No active gateway profile currently established.\n")
        }
        sb.append("\n")

        // 7. Recent In-Memory Ring Buffer Logs
        sb.append("--- [7. IN-MEMORY RING BUFFER LOGS (${DebugLogger.getLogs().size} ENTRIES)] ---\n")
        sb.append(DebugLogger.getLogsAsText())
        sb.append("\n\n")

        // 8. Logcat Diagnostic Slice
        sb.append("--- [8. SYSTEM LOGCAT SLICE] ---\n")
        sb.append(captureLogcatSlice())
        sb.append("\n")

        sb.append("================================================================================\n")
        sb.append("                            END OF DIAGNOSTIC DUMP                              \n")
        sb.append("================================================================================\n")

        return sb.toString()
    }

    /**
     * Sanitizes raw INI or JSON VPN configuration to conceal sensitive cryptographic material
     * (PrivateKey, preshared keys, passwords) while retaining presence, lengths, and hashes for validation.
     */
    fun sanitizeRawConfig(raw: String): String {
        if (raw.isBlank()) return "<empty configuration>"

        var sanitized = raw

        // Sanitize WireGuard / AmneziaWG INI parameters: PrivateKey, PresharedKey
        val iniKeyRegex = Regex("(?i)((?:PrivateKey|PresharedKey)\\s*=\\s*)(.+)")
        sanitized = iniKeyRegex.replace(sanitized) { match ->
            val prefix = match.groupValues[1]
            val secret = match.groupValues[2].trim()
            "$prefix${maskSecret(secret)}"
        }

        // Sanitize JSON parameters: password, secret, secretKey, privateKey, private_key
        val jsonKeyRegex = Regex("(?i)\"([^\"]*(?:password|secret|secretKey|private_key|privateKey)[^\"]*)\"\\s*:\\s*\"([^\"]+)\"")
        sanitized = jsonKeyRegex.replace(sanitized) { match ->
            val key = match.groupValues[1]
            val secret = match.groupValues[2]
            "\"$key\": \"${maskSecret(secret)}\""
        }

        return sanitized
    }

    private fun maskSecret(secret: String): String {
        if (secret.isBlank()) return "<empty>"
        val len = secret.length
        val prefix = secret.take(4)
        val hash = try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(secret.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.takeLast(6)
        } catch (_: Exception) {
            "hash"
        }
        return "<present: len=$len, prefix='$prefix...', sha256_suffix=$hash>"
    }

    private fun captureLogcatSlice(): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "400")
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val lines = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (l.contains("BobrDobr", ignoreCase = true) ||
                    l.contains("Vpn", ignoreCase = true) ||
                    l.contains("OpenConnect", ignoreCase = true) ||
                    l.contains("Amnezia", ignoreCase = true) ||
                    l.contains("wireguard", ignoreCase = true) ||
                    l.contains("AndroidRuntime", ignoreCase = true) ||
                    l.contains("FATAL", ignoreCase = true)
                ) {
                    lines.add(l)
                }
            }
            process.waitFor()

            if (lines.isNotEmpty()) {
                lines.takeLast(300).joinToString("\n")
            } else {
                "No matching logcat entries found for VPN filters."
            }
        } catch (e: Exception) {
            "Failed to capture logcat slice: ${e.message}"
        }
    }
}
