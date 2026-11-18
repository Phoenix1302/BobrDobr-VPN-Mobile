package com.bobrdobr.vpn

import android.app.Application
import android.util.Log
import com.bobrdobr.vpn.subscription.SubscriptionManager

class BobrDobrApp : Application() {

    companion object {
        private const val TAG = "BobrDobrApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BobrDobrApp initialized, starting background services...")

        // Schedule background subscription synchronization with full crash immunity
        try {
            SubscriptionManager.getInstance(this).schedulePeriodicSync(this)
            Log.i(TAG, "Background subscription sync scheduled successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to schedule background WorkManager sync during startup: ${t.message}", t)
        }
    }
}
