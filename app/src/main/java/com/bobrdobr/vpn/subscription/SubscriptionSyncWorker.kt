package com.bobrdobr.vpn.subscription

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background WorkManager worker for periodic subscription state and profile synchronization.
 */
class SubscriptionSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "BobrDobr_SubscriptionSync"
        private const val TAG = "SubscriptionSyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting background subscription synchronization task...")
        val manager = SubscriptionManager.getInstance(applicationContext)
        val url = manager.getActiveSubscriptionUrl()

        if (url.isNullOrBlank()) {
            Log.d(TAG, "No subscription URL configured, skipping worker execution")
            return Result.success()
        }

        val result = manager.fetchSubscription(url)
        return if (result.isSuccess) {
            Log.i(TAG, "Background subscription synchronization successfully completed")
            Result.success()
        } else {
            val err = result.exceptionOrNull()
            val msg = err?.message ?: ""
            if (err is SubscriptionRevokedException || msg.contains("401") || msg.contains("403") || msg.contains("отозван") || msg.contains("заблокирован")) {
                Log.e(TAG, "Subscription token revoked or access forbidden ($msg). Returning Result.failure() without retry.")
                Result.failure()
            } else {
                Log.w(TAG, "Background subscription synchronization failed: $msg. Retrying later.")
                Result.retry()
            }
        }
    }
}
