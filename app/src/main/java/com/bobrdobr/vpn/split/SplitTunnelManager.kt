package com.bobrdobr.vpn.split

import org.bobrdobr.vpn.core.logger.DebugLogger


import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bobrdobr.vpn.core.model.TunnelConfig

/**
 * SplitTunnelManager: Manages Per-App Split Tunneling configuration and persistence.
 *
 * Provides installed package discovery, system vs user filtering, whitelist / blacklist

 * mode switching, and dynamic application of routing rules to [TunnelConfig].
 */
class SplitTunnelManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SplitTunnelManager"
        private const val PREFS_NAME = "bobrdobr_split_tunnel"
        private const val KEY_ENABLED = "split_enabled"
        private const val KEY_MODE = "split_mode"
        private const val KEY_SELECTED_APPS = "selected_packages"

        @Volatile
        private var instance: SplitTunnelManager? = null

        fun getInstance(context: Context): SplitTunnelManager {
            return instance ?: synchronized(this) {
                instance ?: SplitTunnelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _mode = MutableStateFlow(
        try {
            SplitTunnelMode.valueOf(prefs.getString(KEY_MODE, SplitTunnelMode.BLACKLIST.name) ?: SplitTunnelMode.BLACKLIST.name)
        } catch (e: Exception) {
            SplitTunnelMode.BLACKLIST
        }
    )
    val mode: StateFlow<SplitTunnelMode> = _mode.asStateFlow()

    private val _selectedApps = MutableStateFlow<Set<String>>(
        prefs.getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
    )
    val selectedApps: StateFlow<Set<String>> = _selectedApps.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        DebugLogger.i(TAG, "Split tunneling enabled: $enabled")
    }

    fun setMode(mode: SplitTunnelMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        DebugLogger.i(TAG, "Split tunneling mode changed to: ${mode.name}")
    }

    fun toggleApp(packageName: String) {
        val current = _selectedApps.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        _selectedApps.value = current
        prefs.edit().putStringSet(KEY_SELECTED_APPS, current).apply()
    }

    fun selectAll(packages: Collection<String>) {
        val current = _selectedApps.value.toMutableSet()
        current.addAll(packages)
        _selectedApps.value = current
        prefs.edit().putStringSet(KEY_SELECTED_APPS, current).apply()
    }

    fun clearAll() {
        _selectedApps.value = emptySet()
        prefs.edit().remove(KEY_SELECTED_APPS).apply()
    }

    fun getSelectedApps(): Set<String> = _selectedApps.value

    fun getMode(): SplitTunnelMode = _mode.value

    /**
     * Loads installed applications from [PackageManager] on IO dispatcher,
     * classifying each as system or user application.
     */
    suspend fun loadInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPackage = context.packageName

        val selected = _selectedApps.value

        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Error getting installed applications", e)
            emptyList()
        }

        apps.filter { it.packageName != selfPackage }

            .map { appInfo ->
                val label = try {
                    appInfo.loadLabel(pm).toString()
                } catch (e: Exception) {
                    appInfo.packageName
                }
                val icon = try {
                    appInfo.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                AppInfo(
                    name = label.ifBlank { appInfo.packageName },
                    packageName = appInfo.packageName,
                    icon = icon,
                    isSystem = isSystem,
                    isSelected = selected.contains(appInfo.packageName)
                )
            }
            .sortedWith(compareBy({ !it.isSelected }, { it.name.lowercase() }))
    }

    /**
     * Applies configured split-tunnel rules to the target [TunnelConfig].
     */
    fun applyToConfig(config: TunnelConfig): TunnelConfig {
        if (!_isEnabled.value) {
            return config.copy(
                allowedApplications = emptySet(),
                disallowedApplications = emptySet()
            )
        }

        val apps = _selectedApps.value
        return when (_mode.value) {
            SplitTunnelMode.WHITELIST -> {
                DebugLogger.d(TAG, "Applying WHITELIST rules: ${apps.size} packages")
                config.copy(
                    allowedApplications = apps,
                    disallowedApplications = emptySet()
                )
            }
            SplitTunnelMode.BLACKLIST -> {
                DebugLogger.d(TAG, "Applying BLACKLIST rules: ${apps.size} packages")
                config.copy(
                    allowedApplications = emptySet(),
                    disallowedApplications = apps
                )
            }
        }
    }
}
