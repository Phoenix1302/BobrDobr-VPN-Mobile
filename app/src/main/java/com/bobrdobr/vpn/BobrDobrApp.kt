package com.bobrdobr.vpn

import android.app.Application
import com.bobrdobr.vpn.subscription.SubscriptionManager
import org.bobrdobr.vpn.core.logger.DebugLogger

class BobrDobrApp : Application() {

    companion object {
        private const val TAG = "BobrDobrApp"
    }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)

        DebugLogger.i(TAG, "BobrDobrApp initialized (v${BuildConfig.VERSION_NAME}, build ${BuildConfig.VERSION_CODE})")

        // Schedule background subscription synchronization with full crash immunity
        try {
            val subManager = SubscriptionManager.getInstance(this)
            subManager.schedulePeriodicSync(this)
            subManager.syncQuietlyIfOnline()
            DebugLogger.i(TAG, "Background subscription sync scheduled and cold start check launched successfully")
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "Failed to schedule background WorkManager sync during startup: ${t.message}", t)
        }
    }
}
