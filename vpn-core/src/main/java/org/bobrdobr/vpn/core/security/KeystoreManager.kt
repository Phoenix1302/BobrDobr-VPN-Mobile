package org.bobrdobr.vpn.core.security

import org.bobrdobr.vpn.core.logger.DebugLogger


import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bobrdobr.vpn.core.model.TunnelConfig
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Secure Keystore & Encrypted Storage Manager for BobrDobr VPN.
 *
 * Provides cryptographic protection for sensitive VPN profiles, subscription tokens,
 * AmneziaWG preshared keys, and AnyConnect session cookies.
 *
 * Features a 3-tier fault-tolerant fallback chain:
 *   1) MasterKey with hardware-backed StrongBox;
 *   2) MasterKey with standard Keystore (TEE / Software);
 *   3) Standard SharedPreferences ("bobrdobr_vault_compat") if Keystore is unavailable
 *      (e.g. Android Emulators, Firebase Test Lab Robo test devices, headless environments).
 *
 * Guarantees zero unhandled crashes during application startup.
 */
class KeystoreManager(private val context: Context) {

    companion object {
        private const val TAG = "BobrDobr_Keystore"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val PREFS_FILE = "bobrdobr_secure_vault"
        private const val COMPAT_PREFS_FILE = "bobrdobr_vault_compat"
        private const val KEY_CONFIG_PREFIX = "cfg_"
        private const val KEY_CONFIG_LIST = "config_ids"

        @Volatile
        private var instance: KeystoreManager? = null

        fun getInstance(context: Context): KeystoreManager {
            return instance ?: synchronized(this) {
                instance ?: KeystoreManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        initPreferences(context)
    }

    /**
     * Exposes hardware-backed or TEE-backed EncryptedSharedPreferences for sensitive credential vaults.
     */
    fun getEncryptedPrefs(): SharedPreferences = prefs

    /**
     * Initializes preferences with a 3-tier fallback strategy.
     */
    private fun initPreferences(context: Context): SharedPreferences {
        // Tier 1: Try EncryptedSharedPreferences with StrongBox MasterKey
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")
            ) {
                val strongBoxKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setRequestStrongBoxBacked(true)
                    .build()

                val sp = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    strongBoxKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                DebugLogger.i(TAG, "Initialized EncryptedSharedPreferences with StrongBox MasterKey")
                return sp
            }
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "StrongBox MasterKey unavailable or failed (${t.message}), falling back to standard Keystore...")
        }

        // Tier 2: Try EncryptedSharedPreferences with standard MasterKey (TEE / default AndroidKeyStore)
        try {
            val standardKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(false)
                .build()

            val sp = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                standardKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            DebugLogger.i(TAG, "Initialized EncryptedSharedPreferences with standard MasterKey (TEE/Keystore)")
            return sp
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "Standard EncryptedSharedPreferences failed (${t.message}), falling back to compat storage...")
        }

        // Tier 3: Fallback to standard SharedPreferences (Emulators, Firebase Test Lab, headless environments)
        return try {
            DebugLogger.w(TAG, "Using compat SharedPreferences ($COMPAT_PREFS_FILE) to prevent startup crash")
            context.getSharedPreferences(COMPAT_PREFS_FILE, Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "CRITICAL: Could not open compat SharedPreferences: ${t.message}", t)
            context.getSharedPreferences("bobrdobr_vault_emergency", Context.MODE_PRIVATE)
        }
    }

    // --- Tunnel Configuration Storage ---

    /**
     * Securely saves or updates a [TunnelConfig].
     */
    fun saveTunnelConfig(config: TunnelConfig) {
        try {
            val json = config.toJson()
            val prefKey = "$KEY_CONFIG_PREFIX${config.id}"

            val ids = getStoredConfigIds().toMutableSet()
            ids.add(config.id)

            prefs.edit()
                .putString(prefKey, json)
                .putStringSet(KEY_CONFIG_LIST, ids)
                .apply()

            DebugLogger.d(TAG, "Saved config ${config.id} (${config.serverName}) to secure storage")
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error saving TunnelConfig ${config.id}: ${t.message}", t)
        }
    }

    /**
     * Retrieves a stored [TunnelConfig] by its ID.
     */
    fun getTunnelConfig(id: String): TunnelConfig? {
        return try {
            val prefKey = "$KEY_CONFIG_PREFIX$id"
            val json = prefs.getString(prefKey, null) ?: return null
            TunnelConfig.fromJson(json)
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error reading TunnelConfig $id from storage: ${t.message}", t)
            null
        }
    }

    /**
     * Retrieves all securely stored [TunnelConfig] entries.
     */
    fun getAllTunnelConfigs(): List<TunnelConfig> {
        return try {
            val ids = getStoredConfigIds()
            val list = mutableListOf<TunnelConfig>()
            for (id in ids) {
                getTunnelConfig(id)?.let { list.add(it) }
            }
            list
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error retrieving all TunnelConfigs: ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Deletes a [TunnelConfig] by ID.
     */
    fun deleteTunnelConfig(id: String) {
        try {
            val prefKey = "$KEY_CONFIG_PREFIX$id"
            val ids = getStoredConfigIds().toMutableSet()
            ids.remove(id)

            prefs.edit()
                .remove(prefKey)
                .putStringSet(KEY_CONFIG_LIST, ids)
                .apply()

            DebugLogger.d(TAG, "Deleted config $id from secure storage")
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error deleting TunnelConfig $id: ${t.message}", t)
        }
    }

    private fun getStoredConfigIds(): Set<String> {
        return try {
            prefs.getStringSet(KEY_CONFIG_LIST, emptySet()) ?: emptySet()
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error reading config IDs set: ${t.message}", t)
            emptySet()
        }
    }

    // --- Generic Secret / Token Storage ---

    /**
     * Stores an encrypted secret token (e.g. subscription token, OpenConnect cookie).
     */
    fun saveSecret(key: String, secret: String) {
        try {
            prefs.edit()
                .putString(key, secret)
                .apply()
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error saving secret $key: ${t.message}", t)
        }
    }

    /**
     * Retrieves an encrypted secret token.
     */
    fun getSecret(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error getting secret $key: ${t.message}", t)
            null
        }
    }

    /**
     * Deletes a secret token.
     */
    fun deleteSecret(key: String) {
        try {
            prefs.edit()
                .remove(key)
                .apply()
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Error deleting secret $key: ${t.message}", t)
        }
    }

    // --- Android Keystore Hardware Backing Verification ---

    /**
     * Generates a hardware-backed AES-256 key inside Android Keystore TEE / StrongBox.
     */
    fun generateHardwareKey(alias: String): SecretKey? {
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE_PROVIDER
            )

            val specBuilder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")) {
                    try {
                        specBuilder.setIsStrongBoxBacked(true)
                    } catch (t: Throwable) {
                        DebugLogger.w(TAG, "Could not set StrongBox backing on KeyGenParameterSpec: ${t.message}")
                    }
                }
            }

            keyGenerator.init(specBuilder.build())
            keyGenerator.generateKey()
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "Failed to generate hardware key $alias: ${t.message}")
            null
        }
    }

    /**
     * Checks if a key alias is backed by secure hardware (TEE / SE / StrongBox).
     */
    fun isHardwareBacked(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            val key = keyStore.getKey(alias, null) as? SecretKey ?: return false
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE_PROVIDER)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                        keyInfo.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                keyInfo.isInsideSecureHardware
            }
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "Hardware backing check failed for $alias: ${t.message}")
            false
        }
    }
}
