package com.bobrdobr.vpn.subscription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bobrdobr.vpn.R
import com.bobrdobr.vpn.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bobrdobr.vpn.core.security.KeystoreManager
import org.bobrdobr.vpn.core.service.VpnTunnelService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * SubscriptionManager: Centralized coordinator for subscription fetching, parsing,
 * rate limit exponential backoff, background WorkManager synchronization, and lifecycle enforcement.
 */
class SubscriptionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SubscriptionManager"
        private const val PREFS_NAME = "bobrdobr_sub_prefs"
        private const val KEY_SUB_URL = "active_subscription_url"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CABINET_URL = "cabinet_url"
        private const val KEY_IS_FROZEN = "is_frozen"
        private const val KEY_LAST_SYNC = "last_sync"

        private const val USER_AGENT = "BobrDobr-VPN/1.0.0 (Android)"
        private const val TIMEOUT_MS = 15000

        @Volatile
        private var instance: SubscriptionManager? = null

        fun getInstance(context: Context): SubscriptionManager {
            return instance ?: synchronized(this) {
                instance ?: SubscriptionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keystoreManager = KeystoreManager.getInstance(context)
    private val serverRepository = ServerRepository.getInstance(context)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _subscriptionInfo = MutableStateFlow(loadCachedSubscriptionInfo())
    val subscriptionInfo: StateFlow<SubscriptionInfo> = _subscriptionInfo.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    fun getActiveSubscriptionUrl(): String? {
        val url = prefs.getString(KEY_SUB_URL, null)
        return if (!url.isNullOrBlank()) url else null
    }

    fun setActiveSubscriptionUrl(url: String) {
        prefs.edit().putString(KEY_SUB_URL, url.trim()).apply()
    }

    /**
     * Clears active subscription and associated server profiles.
     */
    fun clearSubscription() {
        prefs.edit().clear().apply()
        serverRepository.clearSubscriptionServers()
        _subscriptionInfo.value = SubscriptionInfo()
    }

    /**
     * Fetches subscription from the remote API or base64 endpoint with HTTP 429
     * exponential backoff handling, lifecycle status enforcement, and profile persistence.
     */
    suspend fun fetchSubscription(rawUrl: String): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(rawUrl)
        _isSyncing.value = true
        _syncError.value = null

        val maxRetries = 3
        var attempt = 0
        var backoffMs = 2000L

        while (attempt <= maxRetries) {
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Requesting subscription from: $normalizedUrl (attempt ${attempt + 1}/${maxRetries + 1})")
                val urlObj = URL(normalizedUrl)
                connection = (urlObj.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "HTTP Response Code: $responseCode from $normalizedUrl")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val subUserInfo = connection.getHeaderField("Subscription-Userinfo")
                        var headerExpireSec: Long? = null
                        if (!subUserInfo.isNullOrBlank()) {
                            val match = Regex("""expire=(\d+)""").find(subUserInfo)
                            headerExpireSec = match?.groupValues?.get(1)?.toLongOrNull()
                        }

                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val info = SubscriptionParser.parse(body, normalizedUrl, headerExpireSec)

                        // Enforce lifecycle rules (disconnect tunnel if expired)
                        handleSubscriptionLifecycle(info)

                        // Save cache and profiles
                        persistSubscription(info, normalizedUrl)

                        // Update ServerRepository with fetched servers
                        serverRepository.updateSubscriptionServers(info.profiles.map { it.toServerProfile() })

                        _subscriptionInfo.value = info
                        _isSyncing.value = false
                        return@withContext Result.success(info)
                    }

                    429 -> { // HTTP 429 Too Many Requests -> Backoff
                        attempt++
                        val retryAfterHeader = connection.getHeaderField("Retry-After")
                        val retryAfterSec = retryAfterHeader?.toLongOrNull()
                        val delayDuration = if (retryAfterSec != null && retryAfterSec in 1..60) {
                            retryAfterSec * 1000L
                        } else {
                            backoffMs
                        }

                        Log.w(TAG, "Rate limited (HTTP 429). Backing off for ${delayDuration}ms...")
                        if (attempt <= maxRetries) {
                            delay(delayDuration)
                            backoffMs *= 2 // Exponential backoff: 2s -> 4s -> 8s
                            continue
                        } else {
                            val err = "Лимит запросов к серверу подписки исчерпан (HTTP 429). Повторите позже."
                            _syncError.value = err
                            _isSyncing.value = false
                            val current = _subscriptionInfo.value
                            _subscriptionInfo.value = current.copy(status = SubscriptionStatus.RateLimited)
                            return@withContext Result.failure(IllegalStateException(err))
                        }
                    }

                    HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        val errorStream = connection.errorStream
                        val errBody = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val errJson = try { JSONObject(errBody) } catch (_: Exception) { null }
                        val rawMsg = errJson?.optString("error", errBody) ?: errBody
                        val err = if (rawMsg.isNotBlank()) rawMsg else "Токен подписки отозван или недействителен (HTTP 401)"
                        Log.e(TAG, "Unauthorized (HTTP 401): $err")
                        handleUnauthorizedToken(err, normalizedUrl)
                        _syncError.value = err
                        _isSyncing.value = false
                        return@withContext Result.failure(SubscriptionRevokedException(401, err))
                    }

                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        val errorStream = connection.errorStream
                        val errBody = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val errJson = try { JSONObject(errBody) } catch (_: Exception) { null }
                        val rawMsg = errJson?.optString("error", errBody) ?: errBody
                        val isBlocked = rawMsg.contains("blocked", ignoreCase = true) ||
                                rawMsg.contains("administrator", ignoreCase = true) ||
                                rawMsg.contains("заблокирован", ignoreCase = true) ||
                                rawMsg.contains("бан", ignoreCase = true)

                        val err = if (rawMsg.isNotBlank()) rawMsg else if (isBlocked) "Аккаунт заблокирован администратором" else "Срок действия подписки истек"
                        Log.e(TAG, "Forbidden (HTTP 403, isBlocked=$isBlocked): $err")
                        handleForbiddenSubscription(isBlocked, err, normalizedUrl)
                        _syncError.value = err
                        _isSyncing.value = false
                        return@withContext Result.failure(SubscriptionRevokedException(403, err))
                    }

                    else -> {
                        val errorStream = connection.errorStream
                        val errBody = errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        val err = "Ошибка сервера подписки (HTTP $responseCode): $errBody"
                        Log.e(TAG, err)
                        _syncError.value = err
                        _isSyncing.value = false
                        return@withContext Result.failure(IllegalStateException(err))
                    }
                }
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Subscription fetch error on attempt $attempt: ${e.message}")
                if (attempt <= maxRetries) {
                    delay(backoffMs)
                    backoffMs *= 2
                } else {
                    val msg = "Не удалось подключиться к серверу подписки: ${e.message}"
                    Log.e(TAG, msg, e)
                    _syncError.value = msg
                    _isSyncing.value = false
                    return@withContext Result.failure(e)
                }
            } finally {
                connection?.disconnect()
            }
        }

        _isSyncing.value = false
        Result.failure(IllegalStateException("Исчерпаны попытки подключения"))
    }

    private fun handleUnauthorizedToken(errorMessage: String, url: String) {
        val current = _subscriptionInfo.value
        val updated = current.copy(
            hasSubscription = true,
            url = url,
            status = SubscriptionStatus.InvalidToken,
            isActive = false
        )
        _subscriptionInfo.value = updated
        persistSubscription(updated, url)
        VpnTunnelService.stop(context)
        showSubscriptionNotification(
            title = "Токен подписки недействителен",
            message = "Токен подписки отозван или недействителен. Подключение приостановлено."
        )
    }

    private fun handleForbiddenSubscription(isBlocked: Boolean, errorMessage: String, url: String) {
        val current = _subscriptionInfo.value
        val status = if (isBlocked) SubscriptionStatus.BlockedByAdmin else SubscriptionStatus.Expired
        val updated = current.copy(
            hasSubscription = true,
            url = url,
            status = status,
            isFrozen = if (isBlocked) 2 else 1,
            isActive = false
        )
        _subscriptionInfo.value = updated
        persistSubscription(updated, url)
        VpnTunnelService.stop(context)

        val title = if (isBlocked) "Аккаунт заблокирован" else "Подписка истекла"
        val message = if (isBlocked) {
            "Ваш аккаунт заблокирован администратором панели. Соединение приостановлено."
        } else {
            "Срок действия подписки истек. Соединение приостановлено."
        }
        showSubscriptionNotification(title, message)
    }

    private fun handleSubscriptionLifecycle(info: SubscriptionInfo) {
        if (info.isSubscriptionExpired || info.isAccountBlocked) {
            Log.w(TAG, "Active subscription is expired or blocked. Terminating tunnel.")
            VpnTunnelService.stop(context)
            val title = if (info.isAccountBlocked) "Аккаунт заблокирован" else "Подписка истекла"
            val message = if (info.isAccountBlocked) {
                "Ваш аккаунт заблокирован администратором панели. Соединение приостановлено."
            } else {
                "Срок действия подписки истек. Соединение приостановлено."
            }
            showSubscriptionNotification(title, message)
        }
    }

    fun showSubscriptionNotification(title: String, message: String) {
        try {
            val channelId = "bobrdobr_subscription_alerts"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Уведомления о подписке",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Предупреждения об окончании или блокировке подписки"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_subscription)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(7771, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post subscription notification: ${e.message}")
        }
    }

    private fun persistSubscription(info: SubscriptionInfo, url: String) {
        val statusKey = when (val s = info.currentStatus) {
            is SubscriptionStatus.Active -> if (s.isWarningPeriod) "EXPIRING" else "ACTIVE"
            is SubscriptionStatus.Expired -> "EXPIRED"
            is SubscriptionStatus.BlockedByAdmin -> "BLOCKED"
            is SubscriptionStatus.InvalidToken -> "INVALID_TOKEN"
            is SubscriptionStatus.RateLimited -> "RATE_LIMITED"
            is SubscriptionStatus.NetworkError -> "NETWORK_ERROR"
            is SubscriptionStatus.None -> "NONE"
        }

        prefs.edit()
            .putString(KEY_SUB_URL, url)
            .putString(KEY_LAST_STATUS, statusKey)
            .putString(KEY_USER_ID, info.userId)
            .putString(KEY_USERNAME, info.username)
            .putString(KEY_ROLE, info.role)
            .putString(KEY_SERVER_HOST, info.serverHost)
            .putLong(KEY_EXPIRES_AT, info.expiresAt)
            .putString(KEY_CABINET_URL, info.cabinetUrl)
            .putInt(KEY_IS_FROZEN, info.isFrozen)
            .putLong(KEY_LAST_SYNC, info.lastSyncTime)
            .apply()

        // Securely store parsed tunnel configs via KeystoreManager
        for (profile in info.profiles) {
            try {
                val tunnelCfg = profile.toServerProfile().toTunnelConfig()
                keystoreManager.saveTunnelConfig(tunnelCfg)
            } catch (e: Exception) {
                Log.w(TAG, "Error saving tunnel config to keystore: ${e.message}")
            }
        }
    }

    private fun loadCachedSubscriptionInfo(): SubscriptionInfo {
        val url = prefs.getString(KEY_SUB_URL, "") ?: ""
        if (url.isBlank()) {
            return SubscriptionInfo()
        }

        val statusStr = prefs.getString(KEY_LAST_STATUS, "ACTIVE") ?: "ACTIVE"
        val userId = prefs.getString(KEY_USER_ID, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "Пользователь") ?: "Пользователь"
        val role = prefs.getString(KEY_ROLE, "user") ?: "user"
        val serverHost = prefs.getString(KEY_SERVER_HOST, "") ?: ""
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val cabinetUrl = prefs.getString(KEY_CABINET_URL, "") ?: ""
        val isFrozen = prefs.getInt(KEY_IS_FROZEN, 0)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, System.currentTimeMillis())

        val now = System.currentTimeMillis()
        val daysLeft = if (expiresAt > 0) {
            ((expiresAt - now) / (1000 * 60 * 60 * 24)).coerceAtLeast(0).toInt()
        } else {
            0
        }

        val status: SubscriptionStatus = when {
            isFrozen == 2 || statusStr == "BLOCKED" -> SubscriptionStatus.BlockedByAdmin
            isFrozen == 1 || statusStr == "EXPIRED" || (expiresAt in 1..now) -> SubscriptionStatus.Expired
            statusStr == "INVALID_TOKEN" -> SubscriptionStatus.InvalidToken
            statusStr == "RATE_LIMITED" -> SubscriptionStatus.RateLimited
            else -> {
                val diffMs = if (expiresAt > 0) expiresAt - now else 3650L * 24 * 3600 * 1000L
                val remainingDays = diffMs / (1000L * 60 * 60 * 24)
                val remainingHours = (diffMs % (1000L * 60 * 60 * 24)) / (1000L * 60 * 60)
                val ldt = if (expiresAt > 0) {
                    try {
                        java.time.Instant.ofEpochMilli(expiresAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                    } catch (_: Exception) { null }
                } else null
                SubscriptionStatus.Active(
                    username = username.ifBlank { "Пользователь" },
                    expiresAt = ldt,
                    expiresAtMillis = if (expiresAt > 0) expiresAt else null,
                    remainingDays = remainingDays,
                    remainingHours = remainingHours,
                    isWarningPeriod = remainingDays <= 3
                )
            }
        }

        val cachedProfiles = serverRepository.getSubscriptionServers().map { it.toSubscriptionProfile() }

        return SubscriptionInfo(
            hasSubscription = true,
            url = url,
            cabinetUrl = cabinetUrl.ifBlank { SubscriptionParser.deriveDashboardUrl(url) },
            userId = userId,
            username = username,
            role = role,
            serverHost = serverHost,
            status = status,
            expiresAt = expiresAt,
            daysLeft = daysLeft,
            isFrozen = isFrozen,
            isActive = (status is SubscriptionStatus.Active) && isFrozen == 0,
            profiles = cachedProfiles,
            lastSyncTime = lastSync
        )
    }

    /**
     * Cold start quiet sync: checks online status and updates subscription without spamming errors.
     */
    fun syncQuietlyIfOnline() {
        val url = getActiveSubscriptionUrl() ?: return
        managerScope.launch {
            try {
                if (isOnline()) {
                    Log.d(TAG, "Executing quiet cold-start subscription check...")
                    fetchSubscription(url)
                } else {
                    Log.d(TAG, "Offline during cold start. Checking cached expiration locally.")
                    val current = _subscriptionInfo.value
                    if (current.currentStatus is SubscriptionStatus.Expired) {
                        handleSubscriptionLifecycle(current)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Quiet sync failed (expected when offline): ${e.message}")
            }
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }

    /**
     * Schedules periodic background sync every 6 hours via WorkManager.
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<SubscriptionSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SubscriptionSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
        Log.i(TAG, "Scheduled periodic subscription synchronization (6h interval)")
    }
}

class SubscriptionRevokedException(val statusCode: Int, message: String) : Exception(message)
