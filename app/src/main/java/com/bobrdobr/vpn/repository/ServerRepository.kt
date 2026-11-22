package com.bobrdobr.vpn.repository

import org.bobrdobr.vpn.core.logger.DebugLogger


import android.content.Context
import android.content.SharedPreferences
import com.bobrdobr.vpn.ui.model.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bobrdobr.vpn.core.security.KeystoreManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * ServerRepository: Central registry managing default gateways, subscription servers,
 * and user-added custom AmneziaWG and AnyConnect profiles.
 */
class ServerRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ServerRepository"
        private const val PREFS_NAME = "bobrdobr_servers_vault"
        private const val KEY_CUSTOM_SERVERS = "custom_servers_json"
        private const val KEY_SUB_SERVERS = "subscription_servers_json"

        @Volatile
        private var instance: ServerRepository? = null

        fun getInstance(context: Context): ServerRepository {
            return instance ?: synchronized(this) {
                instance ?: ServerRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = KeystoreManager.getInstance(context).getEncryptedPrefs()

    private val _customServers = mutableListOf<ServerProfile>()
    private val _subscriptionServers = mutableListOf<ServerProfile>()

    private val _allServers = MutableStateFlow<List<ServerProfile>>(emptyList())
    val allServers: StateFlow<List<ServerProfile>> = _allServers.asStateFlow()

    init {
        loadPersistedServers()
        recomputeServersList()
    }

    private fun loadPersistedServers() {
        // Transparent migration from legacy unencrypted SharedPreferences
        try {
            val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var migrated = false
            if (prefs.getString(KEY_CUSTOM_SERVERS, null) == null && legacyPrefs.contains(KEY_CUSTOM_SERVERS)) {
                val legacyCustom = legacyPrefs.getString(KEY_CUSTOM_SERVERS, null)
                if (!legacyCustom.isNullOrEmpty()) {
                    prefs.edit().putString(KEY_CUSTOM_SERVERS, legacyCustom).apply()
                    migrated = true
                }
            }
            if (prefs.getString(KEY_SUB_SERVERS, null) == null && legacyPrefs.contains(KEY_SUB_SERVERS)) {
                val legacySub = legacyPrefs.getString(KEY_SUB_SERVERS, null)
                if (!legacySub.isNullOrEmpty()) {
                    prefs.edit().putString(KEY_SUB_SERVERS, legacySub).apply()
                    migrated = true
                }
            }
            if (migrated || legacyPrefs.contains(KEY_CUSTOM_SERVERS) || legacyPrefs.contains(KEY_SUB_SERVERS)) {
                legacyPrefs.edit().clear().apply()
                DebugLogger.i(TAG, "Migrated legacy server vault to hardware/TEE-backed Keystore storage")
            }
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Legacy server vault migration check failed: ${e.message}")
        }

        // 1. Load custom servers
        val customJson = prefs.getString(KEY_CUSTOM_SERVERS, null)
        if (!customJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(customJson)
                _customServers.clear()
                for (i in 0 until arr.length()) {
                    _customServers.add(ServerProfile.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Error loading custom servers", e)
            }
        }

        // 2. Load cached subscription servers
        val subJson = prefs.getString(KEY_SUB_SERVERS, null)
        if (!subJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(subJson)
                _subscriptionServers.clear()
                for (i in 0 until arr.length()) {
                    _subscriptionServers.add(ServerProfile.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Error loading subscription servers", e)
            }
        }
    }

    private fun recomputeServersList() {
        val list = mutableListOf<ServerProfile>()

        // Subscription servers first if present
        list.addAll(_subscriptionServers)

        // Custom user-added servers
        list.addAll(_customServers)

        // If neither subscription nor custom exist, servers list remains empty (no fake demo servers)
        _allServers.value = list
    }

    /**
     * Adds a user-created server and persists to local storage.
     */
    fun addCustomServer(server: ServerProfile) {
        val updated = server.copy(isCustom = true)
        _customServers.removeAll { it.id == updated.id }
        _customServers.add(0, updated)
        saveCustomServers()
        recomputeServersList()
    }

    /**
     * Removes a server by its identifier.
     */
    fun removeServer(id: String) {
        _customServers.removeAll { it.id == id }
        _subscriptionServers.removeAll { it.id == id }
        saveCustomServers()
        saveSubscriptionServers()
        recomputeServersList()
    }

    /**
     * Updates servers retrieved from subscription API.
     */
    fun updateSubscriptionServers(servers: List<ServerProfile>) {
        _subscriptionServers.clear()
        _subscriptionServers.addAll(servers.map { it.copy(isFromSubscription = true) })
        saveSubscriptionServers()
        recomputeServersList()
    }

    /**
     * Clears all subscription servers (e.g. on subscription logout).
     */
    fun clearSubscriptionServers() {
        _subscriptionServers.clear()
        saveSubscriptionServers()
        recomputeServersList()
    }

    /**
     * Returns an immutable copy of currently cached subscription servers.
     */
    fun getSubscriptionServers(): List<ServerProfile> {
        return _subscriptionServers.toList()
    }

    private fun saveCustomServers() {
        val arr = JSONArray()
        for (s in _customServers) {
            arr.put(s.toJson())
        }
        prefs.edit().putString(KEY_CUSTOM_SERVERS, arr.toString()).apply()
    }

    private fun saveSubscriptionServers() {
        val arr = JSONArray()
        for (s in _subscriptionServers) {
            arr.put(s.toJson())
        }
        prefs.edit().putString(KEY_SUB_SERVERS, arr.toString()).apply()
    }
}
