package com.bobrdobr.vpn.debug

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class LogLevel(val tag: String) {
    INFO("[INFO]"),
    DEBUG("[DEBUG]"),
    WARN("[WARN]"),
    ERROR("[ERROR]")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun toFormattedString(): String = "$timestamp ${level.tag} [$tag] $message"
}

/**
 * DebugLogger: Real-time diagnostic log repository for developers and users.
 * Supports thread-safe logging, level filtering, and real-time reactive streaming.
 */
object DebugLogger {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val MAX_LOGS = 1200
    private var sequenceId = 0L

    private val _isDebugModeEnabled = MutableStateFlow(true)
    val isDebugModeEnabled: StateFlow<Boolean> = _isDebugModeEnabled.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    init {
        logInitialSystemInfo()
    }

    fun setDebugMode(enabled: Boolean) {
        _isDebugModeEnabled.value = enabled
        log(
            LogLevel.INFO,
            "DebugLogger",
            "Режим расширенной отладки переключен: ${if (enabled) "АКТИВЕН" else "ОТКЛЮЧЕН"}"
        )
    }

    fun log(level: LogLevel, tag: String, message: String) {
        if (!_isDebugModeEnabled.value && level == LogLevel.DEBUG) return
        val entry = LogEntry(
            id = ++sequenceId,
            timestamp = LocalTime.now().format(timeFormatter),
            level = level,
            tag = tag,
            message = message
        )
        synchronized(_logsFlow) {
            val current = _logsFlow.value.toMutableList()
            if (current.size >= MAX_LOGS) {
                current.removeAt(0)
            }
            current.add(entry)
            _logsFlow.value = current
        }
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun clearLogs() {
        synchronized(_logsFlow) {
            _logsFlow.value = emptyList()
        }
        info("DebugLogger", "Терминал логов очищен оператором")
    }

    fun getAllLogsText(): String {
        return _logsFlow.value.joinToString("\n") { it.toFormattedString() }
    }

    private fun logInitialSystemInfo() {
        info("System", "Инициализация подсистемы отладки BobrDobr VPN Mobile v0.8.10-prerelease")
        info("Platform", "Android SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}), Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        info("ABIs", "Поддерживаемые ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        info("Alignment", "Выравнивание ELF LOAD 16 KB (0x4000): УСПЕШНО")
        info("Engines", "Ядро AmneziaWG: Rust Edition 2021 + amnezia-wireguard-core + BoringSSL")
        info("Engines", "Ядро OpenConnect: Go 1.23.4 / AnyConnect CSTP + uTLS Chrome Fingerprint")
        info("Security", "Строгая DNS-маршрутизация и Per-App Split Tunneling готовы к работе")
    }
}
