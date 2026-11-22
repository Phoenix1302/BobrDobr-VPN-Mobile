package com.bobrdobr.vpn.subscription

import com.bobrdobr.vpn.ui.model.ServerProfile
import org.bobrdobr.vpn.core.model.VpnProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Operational lifecycle status of the BobrDobr VPN subscription.
 * Sealed hierarchy providing precise domain states for active, warning, expired, blocked, and rate-limited.
 */
sealed interface SubscriptionStatus {
    val displayName: String

    data class Active(
        val username: String = "Пользователь",
        val expiresAt: java.time.LocalDateTime? = null,
        val expiresAtMillis: Long? = null,
        val remainingDays: Long = 0L,
        val remainingHours: Long = 0L,
        val isWarningPeriod: Boolean = false
    ) : SubscriptionStatus {
        override val displayName: String
            get() = if (isWarningPeriod) "Истекает скоро" else "Активна"

        val formattedRemaining: String
            get() = when {
                remainingDays > 0 -> "$remainingDays дн. $remainingHours ч."
                remainingHours > 0 -> "$remainingHours ч."
                else -> "Менее часа"
            }
    }

    data object Expired : SubscriptionStatus {
        override val displayName: String = "Срок действия истек"
    }

    data object BlockedByAdmin : SubscriptionStatus {
        override val displayName: String = "Заблокирован администратором"
    }

    data object InvalidToken : SubscriptionStatus {
        override val displayName: String = "Токен недействителен или отозван"
    }

    data object RateLimited : SubscriptionStatus {
        override val displayName: String = "Превышен лимит запросов"
    }

    data class NetworkError(val message: String) : SubscriptionStatus {
        override val displayName: String = "Ошибка сети: $message"
    }

    data object None : SubscriptionStatus {
        override val displayName: String = "Нет подписки"
    }

    companion object {
        val ACTIVE = Active()
        val EXPIRING = Active(isWarningPeriod = true)
        val EXPIRED = Expired
        val BLOCKED = BlockedByAdmin
        val BANNED = BlockedByAdmin
        val FROZEN = Expired
        val UNKNOWN = None

        fun fromString(value: String): SubscriptionStatus {
            return when (value.lowercase().trim()) {
                "active" -> ACTIVE
                "expiring" -> EXPIRING
                "expired" -> EXPIRED
                "blocked", "banned" -> BLOCKED
                "frozen" -> FROZEN
                "invalid", "unauthorized" -> InvalidToken
                "rate_limited", "429" -> RateLimited
                else -> UNKNOWN
            }
        }
    }
}

val SubscriptionStatus.canConnect: Boolean
    get() = when (this) {
        is SubscriptionStatus.Active -> true
        is SubscriptionStatus.RateLimited -> true
        is SubscriptionStatus.NetworkError -> true
        is SubscriptionStatus.Expired,
        is SubscriptionStatus.BlockedByAdmin,
        is SubscriptionStatus.InvalidToken,
        is SubscriptionStatus.None -> false
    }

val SubscriptionStatus.isWarning: Boolean
    get() = (this as? SubscriptionStatus.Active)?.isWarningPeriod == true || this is SubscriptionStatus.RateLimited

val SubscriptionStatus.isCritical: Boolean
    get() = this is SubscriptionStatus.Expired ||
            this is SubscriptionStatus.BlockedByAdmin ||
            this is SubscriptionStatus.InvalidToken

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

fun ServerProfile.toSubscriptionProfile(): SubscriptionProfile {
    return SubscriptionProfile(
        id = id,
        name = name,
        protocol = protocol,
        serverAddress = serverAddress,
        serverPort = serverPort,
        location = location,
        countryCode = countryCode,
        flagEmoji = flagEmoji,
        pingMs = pingMs,
        loadPercent = loadPercent,
        isRecommended = isRecommended,
        dnsServers = dnsServers,
        configText = configText,
        username = username,
        password = password,
        group = group,
        secret = secret,
        rawConfigJson = rawConfigJson ?: "{}"
    )
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
    val status: SubscriptionStatus = SubscriptionStatus.None,
    val expiresAt: Long = 0L,         // Epoch timestamp in milliseconds (0 = unlimited)
    val daysLeft: Int = 0,
    val isFrozen: Int = 0,           // 0 = Active, 1 = Expired, 2 = Blocked
    val isActive: Boolean = false,
    val trafficUsedBytes: Long = 0L,
    val trafficLimitBytes: Long = 0L, // 0 = unlimited
    val profiles: List<SubscriptionProfile> = emptyList(),
    val lastSyncTime: Long = 0L
) {
    val isAccountBlocked: Boolean
        get() = isFrozen == 2 || status is SubscriptionStatus.BlockedByAdmin

    val isSubscriptionExpired: Boolean
        get() = isFrozen == 1 || status is SubscriptionStatus.Expired || (expiresAt in 1..System.currentTimeMillis())

    /**
     * Dynamically computes the real-time status against device clock,
     * ensuring immediate UI and VPN shutdown when expiry threshold is crossed.
     */
    fun computeStatus(nowMillis: Long = System.currentTimeMillis()): SubscriptionStatus {
        if (!hasSubscription) {
            return SubscriptionStatus.None
        }
        if (isAccountBlocked || isFrozen == 2) {
            return SubscriptionStatus.BlockedByAdmin
        }
        if (isFrozen == 1 || (expiresAt in 1..nowMillis)) {
            return SubscriptionStatus.Expired
        }
        if (status is SubscriptionStatus.InvalidToken) {
            return SubscriptionStatus.InvalidToken
        }
        if (status is SubscriptionStatus.BlockedByAdmin) {
            return SubscriptionStatus.BlockedByAdmin
        }
        if (status is SubscriptionStatus.Expired) {
            return SubscriptionStatus.Expired
        }
        if (status is SubscriptionStatus.RateLimited) {
            return SubscriptionStatus.RateLimited
        }
        if (status is SubscriptionStatus.NetworkError) {
            return status
        }

        val diffMs = if (expiresAt > 0) expiresAt - nowMillis else 3650L * 24 * 3600 * 1000L
        if (diffMs <= 0) {
            return SubscriptionStatus.Expired
        }

        val remainingDays = diffMs / (1000L * 60 * 60 * 24)
        val remainingHours = (diffMs % (1000L * 60 * 60 * 24)) / (1000L * 60 * 60)
        val isWarning = remainingDays <= 3

        val ldt = if (expiresAt > 0) {
            try {
                java.time.Instant.ofEpochMilli(expiresAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            } catch (_: Exception) { null }
        } else null

        return SubscriptionStatus.Active(
            username = username.ifBlank { "Пользователь" },
            expiresAt = ldt,
            expiresAtMillis = if (expiresAt > 0) expiresAt else null,
            remainingDays = remainingDays,
            remainingHours = remainingHours,
            isWarningPeriod = isWarning
        )
    }

    val currentStatus: SubscriptionStatus
        get() = computeStatus()

    fun getRenewalUrl(): String {
        val derived = SubscriptionParser.deriveDashboardUrl(url)
        if (derived.isNotBlank()) {
            return derived
        }
        if (cabinetUrl.isNotBlank()) {
            return cabinetUrl
        }
        return "https://bobrdobr.org"
    }

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
        get() = when (val s = currentStatus) {
            is SubscriptionStatus.Active -> s.formattedRemaining
            is SubscriptionStatus.Expired -> "Срок действия истек"
            is SubscriptionStatus.BlockedByAdmin -> "Заблокировано администратором"
            is SubscriptionStatus.InvalidToken -> "Токен недействителен"
            is SubscriptionStatus.RateLimited -> "Лимит запросов к серверу"
            is SubscriptionStatus.NetworkError -> "Сбой сети при проверке"
            is SubscriptionStatus.None -> "Нет подписки"
        }
}
