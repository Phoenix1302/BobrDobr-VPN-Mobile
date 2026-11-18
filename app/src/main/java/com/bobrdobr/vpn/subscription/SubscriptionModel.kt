package com.bobrdobr.vpn.subscription

import com.bobrdobr.vpn.ui.model.ServerProfile
import org.bobrdobr.vpn.core.model.VpnProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Operational lifecycle status of the BobrDobr VPN subscription.
 */
enum class SubscriptionStatus(val displayName: String) {
    ACTIVE("Активна"),
    EXPIRING("Истекает скоро"),
    EXPIRED("Срок действия истек"),
    BLOCKED("Заблокирована"),
    BANNED("Аккаунт забанен"),
    FROZEN("Заморожена"),
    UNKNOWN("Неизвестно");

    companion object {
        fun fromString(value: String): SubscriptionStatus {
            return when (value.lowercase().trim()) {
                "active" -> ACTIVE
                "expiring" -> EXPIRING
                "expired" -> EXPIRED
                "blocked" -> BLOCKED
                "banned" -> BANNED
                "frozen" -> FROZEN
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Profile parsed from remote subscription.
 */
data class SubscriptionProfile(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,
    val serverAddress: String,
    val serverPort: Int,
    val location: String = "Европа",
    val countryCode: String = "DE",
    val flagEmoji: String = "🌐",
    val pingMs: Int = 30,
    val loadPercent: Int = 30,
    val isRecommended: Boolean = false,
    val dnsServers: List<String> = emptyList(),
    val configText: String? = null,
    val username: String? = null,
    val password: String? = null,
    val group: String? = null,
    val secret: String? = null,
    val rawConfigJson: String = "{}"
) {
    fun toServerProfile(): ServerProfile {
        return ServerProfile(
            id = id,
            name = name,
            location = location,
            countryCode = countryCode,
            flagEmoji = flagEmoji,
            protocol = protocol,
            serverAddress = serverAddress,
            serverPort = serverPort,
            pingMs = pingMs,
            loadPercent = loadPercent,
            isRecommended = isRecommended,
            dnsServers = dnsServers,
            configText = configText,
            username = username,
            password = password,
            group = group,
            secret = secret ?: group,
            isFromSubscription = true,
            rawConfigJson = rawConfigJson
        )
    }
}

/**
 * Holds complete metadata, account state, and profiles from subscription provider.
 */
data class SubscriptionInfo(
    val hasSubscription: Boolean = false,
    val url: String = "",
    val cabinetUrl: String = "",
    val userId: String = "",
    val username: String = "",
    val role: String = "user",
    val serverHost: String = "",
    val status: SubscriptionStatus = SubscriptionStatus.UNKNOWN,
    val expiresAt: Long = 0L,         // Epoch timestamp in milliseconds
    val daysLeft: Int = 0,
    val isFrozen: Int = 0,           // 0 = Active, 1 = Expired, 2 = Blocked
    val isActive: Boolean = false,
    val trafficUsedBytes: Long = 0L,
    val trafficLimitBytes: Long = 0L, // 0 = unlimited
    val profiles: List<SubscriptionProfile> = emptyList(),
    val lastSyncTime: Long = 0L
) {
    val isAccountBlocked: Boolean
        get() = isFrozen == 2 || status == SubscriptionStatus.BLOCKED || status == SubscriptionStatus.BANNED

    val isSubscriptionExpired: Boolean
        get() = isFrozen == 1 || status == SubscriptionStatus.EXPIRED || (expiresAt > 0 && expiresAt < System.currentTimeMillis())

    val formattedExpirationDate: String
        get() {
            if (expiresAt <= 0) return "Бессрочно"
            return try {
                val sdf = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
                sdf.format(Date(expiresAt))
            } catch (e: Exception) {
                "Неизвестно"
            }
        }

    val formattedDaysLeft: String
        get() = when {
            !hasSubscription -> "Нет подписки"
            isAccountBlocked -> "Заблокировано администратором"
            isSubscriptionExpired -> "Срок действия истек"
            daysLeft > 0 -> "$daysLeft дн. защиты"
            expiresAt > 0 -> "${((expiresAt - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).coerceAtLeast(0)} дн. защиты"
            else -> "Бессрочная лицензия"
        }
}
