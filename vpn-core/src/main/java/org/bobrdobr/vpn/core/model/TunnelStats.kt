package org.bobrdobr.vpn.core.model

import java.util.Locale

/**
 * Real-time traffic, throughput, and link latency metrics.
 */
data class TunnelStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxPackets: Long = 0L,
    val txPackets: Long = 0L,
    val rxSpeedBytesPerSec: Long = 0L,
    val txSpeedBytesPerSec: Long = 0L,
    val pingMs: Long = 0L,
    val lastHandshakeTimestamp: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Formats raw bytes into human-readable notation (e.g., "1.4 MB", "350 KB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    /**
     * Formats bytes per second into network speed notation (e.g., "12.5 Mbps").
     */
    fun formatSpeed(speedBytesPerSec: Long): String {
        val bitsPerSec = speedBytesPerSec * 8
        if (bitsPerSec < 1000) return "$bitsPerSec bps"
        val exp = (Math.log(bitsPerSec.toDouble()) / Math.log(1000.0)).toInt()
        val pre = "kMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %sbps", bitsPerSec / Math.pow(1000.0, exp.toDouble()), pre)
    }

    val formattedRxSpeed: String get() = formatSpeed(rxSpeedBytesPerSec)
    val formattedTxSpeed: String get() = formatSpeed(txSpeedBytesPerSec)
    val formattedRxTotal: String get() = formatBytes(rxBytes)
    val formattedTxTotal: String get() = formatBytes(txBytes)

    companion object {
        val EMPTY = TunnelStats()
    }
}
