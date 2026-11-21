package com.bobrdobr.vpn.subscription

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bobrdobr.vpn.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bobrdobr.vpn.core.security.KeystoreManager
import org.bobrdobr.vpn.core.service.VpnTunnelService
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
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val info = SubscriptionParser.parse(body, normalizedUrl)

                        // Enforce lifecycle rules
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
                            return@withContext Result.failure(IllegalStateException(err))
                        }
                    }

                    HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        val err = "Токен подписки отозван или недействителен (HTTP 401)"
                        Log.e(TAG, err)
                        handleRevokedToken()
                        _syncError.value = err
                        _isSyncing.value = false
                        return@withContext Result.failure(SubscriptionRevokedException(401, err))
                    }

                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        val err = "Доступ заблокирован (HTTP 403): учетная запись заморожена"
                        Log.e(TAG, err)
                        handleRevokedToken()
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

    private fun handleSubscriptionLifecycle(info: SubscriptionInfo) {
        if (info.isSubscriptionExpired || info.isAccountBlocked) {
            Log.w(TAG, "Active subscription is expired or blocked. Terminating tunnel.")
            VpnTunnelService.stop(context)
        }
    }

    private fun handleRevokedToken() {
        val current = _subscriptionInfo.value
        val updated = current.copy(
            status = SubscriptionStatus.BLOCKED,
            isFrozen = 2,
            isActive = false
        )
        _subscriptionInfo.value = updated
        persistSubscription(updated, current.url)
        VpnTunnelService.stop(context)
    }

    private fun persistSubscription(info: SubscriptionInfo, url: String) {
        prefs.edit()
            .putString(KEY_SUB_URL, url)
            .putString(KEY_LAST_STATUS, info.status.name)
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

        val statusStr = prefs.getString(KEY_LAST_STATUS, SubscriptionStatus.ACTIVE.name) ?: SubscriptionStatus.ACTIVE.name
        val status = try { SubscriptionStatus.valueOf(statusStr) } catch (e: Exception) { SubscriptionStatus.ACTIVE }
        val userId = prefs.getString(KEY_USER_ID, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "Egor") ?: "Egor"
        val role = prefs.getString(KEY_ROLE, "user") ?: "user"
        val serverHost = prefs.getString(KEY_SERVER_HOST, "") ?: ""
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val cabinetUrl = prefs.getString(KEY_CABINET_URL, "") ?: ""
        val isFrozen = prefs.getInt(KEY_IS_FROZEN, 0)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, System.currentTimeMillis())

        val daysLeft = if (expiresAt > 0) {
            ((expiresAt - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).coerceAtLeast(0).toInt()
        } else {
            0
        }

        val cachedProfiles = serverRepository.getSubscriptionServers().map { it.toSubscriptionProfile() }

        return SubscriptionInfo(
            hasSubscription = true,
            url = url,
            cabinetUrl = cabinetUrl,
            userId = userId,
            username = username,
            role = role,
            serverHost = serverHost,
            status = status,
            expiresAt = expiresAt,
            daysLeft = daysLeft,
            isFrozen = isFrozen,
            isActive = (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.EXPIRING) && isFrozen == 0,
            profiles = cachedProfiles,
            lastSyncTime = lastSync
        )
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
