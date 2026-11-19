package org.bobrdobr.vpn.core.logger

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Log severity levels for BobrDobr VPN debug subsystem.
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * Single structured log entry with millisecond precision timestamp.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null
) {
    fun toFormattedString(dateFormat: SimpleDateFormat): String {
        val dateStr = dateFormat.format(Date(timestamp))
        val lvlChar = level.name.first()
        val base = "[$dateStr] [$lvlChar] [$tag] $message"
        return if (throwable != null) {
            "$base\n${Log.getStackTraceString(throwable)}"
        } else {
            base
        }
    }
}

/**
 * Thread-safe ring-buffer logger for in-depth diagnostics, reactive UI terminal streaming,
 * and comprehensive system dump exports.
 */
object DebugLogger {

    fun interface LogListener {
        fun onLog(entry: LogEntry)
    }

    private const val MAX_ENTRIES = 1500
    private const val PREFS_NAME = "bobrdobr_debug_logger_prefs"
    private const val KEY_DEBUG_ENABLED = "key_debug_enabled"

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val listeners = CopyOnWriteArrayList<LogListener>()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val _isDebugModeEnabled = MutableStateFlow(true)
    val isDebugModeEnabled: StateFlow<Boolean> = _isDebugModeEnabled.asStateFlow()

    /**
     * Initializes logger preferences from persistent storage.
     * Defaults to enabled (true) for pre-release diagnostics.
     */
    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_DEBUG_ENABLED, true)
            _isDebugModeEnabled.value = enabled
        } catch (_: Exception) {}
    }

    /**
     * Toggles extended debug logging mode and persists user preference.
     */
    fun setDebugMode(context: Context, enabled: Boolean) {
        _isDebugModeEnabled.value = enabled
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_DEBUG_ENABLED, enabled).apply()
        } catch (_: Exception) {}
    }

    fun v(tag: String, message: String) {
        Log.v(tag, message)
        appendEntry(LogLevel.VERBOSE, tag, message, null)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        appendEntry(LogLevel.DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        appendEntry(LogLevel.INFO, tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        appendEntry(LogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        appendEntry(LogLevel.ERROR, tag, message, throwable)
    }

    fun addLogListener(listener: LogListener) {
        listeners.add(listener)
    }

    fun removeLogListener(listener: LogListener) {
        listeners.remove(listener)
    }

    private fun appendEntry(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        val updatedList: List<LogEntry>
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.pollFirst()
            }
            buffer.addLast(entry)
            updatedList = buffer.toList()
        }
        _logsFlow.value = updatedList

        for (l in listeners) {
            try {
                l.onLog(entry)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Returns an immutable snapshot copy of current logs in memory.
     */
    fun getLogs(): List<LogEntry> {
        return synchronized(lock) {
            buffer.toList()
        }
    }

    /**
     * Formats all buffered logs into a single UTF-8 multi-line string.
     */
    fun getLogsAsText(): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return synchronized(lock) {
            buffer.joinToString("\n") { it.toFormattedString(df) }
        }
    }

    /**
     * Clears the in-memory ring buffer.
     */
    fun clearLogs() {
        synchronized(lock) {
            buffer.clear()
        }
        _logsFlow.value = emptyList()
    }
}
