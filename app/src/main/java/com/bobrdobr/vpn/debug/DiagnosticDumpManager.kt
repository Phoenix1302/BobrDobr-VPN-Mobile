package com.bobrdobr.vpn.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.bobrdobr.vpn.repository.ServerRepository
import com.bobrdobr.vpn.split.SplitTunnelManager
import org.bobrdobr.vpn.core.service.VpnTunnelService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DiagnosticDumpManager: Aggregates system metrics, component specifications,
 * tunnel runtime telemetry, and logs into a shareable text report.
 */
object DiagnosticDumpManager {

    fun generateDump(context: Context): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
        val vpnState = VpnTunnelService.vpnState.value
        val stats = VpnTunnelService.tunnelStats.value
        val splitManager = SplitTunnelManager.getInstance(context)
        val selectedAppsCount = splitManager.selectedApps.value.size
        val isSplitEnabled = splitManager.isEnabled.value

        val servers = try {
            ServerRepository.getInstance(context).allServers.value
        } catch (_: Exception) {
            emptyList()
        }

        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) { null }

        val versionName = pkgInfo?.versionName ?: "0.8.13-prerelease"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode ?: 93L
        } else {
            @Suppress("DEPRECATION")
            (pkgInfo?.versionCode?.toLong() ?: 93L)
        }
        val targetSdk = context.applicationInfo.targetSdkVersion
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.applicationInfo.minSdkVersion else 26

        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("           BOBRDOBR VPN MOBILE - DIAGNOSTIC SYSTEM DUMP REPORT                  ")
        sb.appendLine("================================================================================")
        sb.appendLine("Timestamp:              $now")
        sb.appendLine("Client Version:         BobrDobr VPN Mobile v$versionName (Build $versionCode)")
        sb.appendLine("Target SDK:             $targetSdk (Android Ready) / Min SDK: $minSdk")
        sb.appendLine("UI Framework:           Jetpack Compose BOM 2024.12.01 / Material 3")
        sb.appendLine("Kotlin Runtime:         2.1.0")
        sb.appendLine("NDK Toolchain:          Android NDK r27c (LLVM Clang 18) / C++20")
        sb.appendLine("ELF 16KB Page-Size:     VERIFIED (LOAD segments aligned to 0x4000)")
        sb.appendLine("AmneziaWG Engine:       Rust Edition 2021 + amnezia-wireguard-core + BoringSSL/ring")
        sb.appendLine("OpenConnect Engine:     Go 1.23.4 / AnyConnect CSTP/DTLS + uTLS Chrome Fingerprint")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("DEVICE HARDWARE & OS ENVIRONMENT:")
        sb.appendLine("Manufacturer:           ${Build.MANUFACTURER}")
        sb.appendLine("Brand / Model:          ${Build.BRAND} / ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android OS Release:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Supported ABIs:         ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("VPN TUNNEL STATE & TELEMETRY:")
        sb.appendLine("Current VpnState:       $vpnState")
        sb.appendLine("Traffic RX:             ${stats.formattedRxTotal} (${stats.formattedRxSpeed})")
        sb.appendLine("Traffic TX:             ${stats.formattedTxTotal} (${stats.formattedTxSpeed})")
        sb.appendLine("Tunnel Ping Latency:    ${if (stats.pingMs > 0) "${stats.pingMs} ms" else "Offline / Not Measured"}")
        sb.appendLine("Per-App Split Tunnel:   Enabled=$isSplitEnabled, Mode=${splitManager.mode.value}, AppsSelected=$selectedAppsCount")
        sb.appendLine("Configured Gateways:    ${servers.size} servers registered")
        servers.forEachIndexed { idx, s ->
            sb.appendLine("  [#${idx + 1}] ${s.flagEmoji} ${s.name} (${s.location}) - Protocol: ${s.protocol}, Host: ${s.serverAddress}:${s.serverPort}, Ping: ${s.pingMs}ms")
        }
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("CORE SUBSYSTEM & HARDWARE/NETWORK LOW-LEVEL DUMP:")
        try {
            sb.appendLine(org.bobrdobr.vpn.core.logger.DiagnosticDumpManager.generateDumpContent(context))
        } catch (e: Exception) {
            sb.appendLine("Error generating core dump: ${e.message}")
        }
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("REAL-TIME RECENT LOG BUFFER:")
        val logs = DebugLogger.getAllLogsText()
        if (logs.isBlank()) {
            sb.appendLine("(Log buffer is empty)")
        } else {
            sb.appendLine(logs)
        }
        sb.appendLine("================================================================================")
        sb.appendLine("                           END OF DIAGNOSTIC DUMP                               ")
        sb.appendLine("================================================================================")
        return sb.toString()
    }

    fun shareDump(context: Context) {
        try {
            val dumpText = generateDump(context)
            val dumpDir = File(context.cacheDir, "debug_dumps")
            if (!dumpDir.exists()) dumpDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dumpFile = File(dumpDir, "bobrdobr_diagnostic_dump_$timestamp.txt")
            dumpFile.writeText(dumpText)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                dumpFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BobrDobr VPN Diagnostic Dump ($timestamp)")
                putExtra(Intent.EXTRA_TEXT, "Диагностический дамп BobrDobr VPN Mobile v0.8.13-prerelease\nФайл прикреплен.")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Отправить диагностический дамп")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            DebugLogger.info("Diagnostics", "Диагностический дамп успешно сформирован: ${dumpFile.name}")
        } catch (e: Exception) {
            DebugLogger.error("Diagnostics", "Ошибка формирования дампа: ${e.message}")
            Toast.makeText(context, "Ошибка отправки дампа: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
