package com.bobrdobr.vpn

import android.app.Application
import com.bobrdobr.vpn.subscription.SubscriptionManager
import org.bobrdobr.vpn.core.logger.DebugLogger as CoreLogger
import com.bobrdobr.vpn.debug.DebugLogger as UiLogger
import com.bobrdobr.vpn.debug.LogLevel as UiLogLevel

class BobrDobrApp : Application() {

    companion object {
        private const val TAG = "BobrDobrApp"
    }

    override fun onCreate() {
        super.onCreate()
        CoreLogger.init(this)

        // Bridge native and VPN core logs into the UI real-time terminal
        CoreLogger.addLogListener { entry ->
            val uiLevel = when (entry.level) {
                org.bobrdobr.vpn.core.logger.LogLevel.VERBOSE,
                org.bobrdobr.vpn.core.logger.LogLevel.DEBUG -> UiLogLevel.DEBUG
                org.bobrdobr.vpn.core.logger.LogLevel.INFO -> UiLogLevel.INFO
                org.bobrdobr.vpn.core.logger.LogLevel.WARN -> UiLogLevel.WARN
                org.bobrdobr.vpn.core.logger.LogLevel.ERROR -> UiLogLevel.ERROR
            }
            UiLogger.log(uiLevel, entry.tag, entry.message)
        }

        CoreLogger.i(TAG, "BobrDobrApp initialized (v0.8.8-prerelease, build 88)")

        // Schedule background subscription synchronization with full crash immunity
        try {
            SubscriptionManager.getInstance(this).schedulePeriodicSync(this)
            CoreLogger.i(TAG, "Background subscription sync scheduled successfully")
        } catch (t: Throwable) {
            CoreLogger.e(TAG, "Failed to schedule background WorkManager sync during startup: ${t.message}", t)
        }
    }
}
