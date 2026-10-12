package com.agentnotify.app.data

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.agentnotify.app.AgentNotifyApplication
import com.agentnotify.app.messaging.Notifications
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Downloads an attachment as a foreground job so it survives the app being backgrounded,
 * the screen turning off and Doze. WorkManager persists the job, so it also resumes after
 * the process is killed, and retries transient network failures with backoff.
 */
class AttachmentDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val messageId = inputData.getLong(KEY_MESSAGE_ID, -1)
    private val repository get() = (applicationContext as AgentNotifyApplication).repository

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(bytesWritten = 0)

    override suspend fun doWork(): Result {
        val message = repository.find(messageId) ?: return Result.failure()
        setForeground(foregroundInfo(bytesWritten = 0))
        var lastUpdate = 0L
        return try {
            repository.fetchAttachment(messageId) { written ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                    lastUpdate = now
                    setForeground(foregroundInfo(written))
                }
            }
            repository.find(messageId)?.let { Notifications.showDownloadResult(applicationContext, it) }
            Result.success()
        } catch (cancelled: CancellationException) {
            // Stopped by the system (rescheduled) or by deleting the message; leave state as is.
            throw cancelled
        } catch (error: IOException) {
            if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
            fail(message, error)
        } catch (error: Exception) {
            fail(message, error)
        }
    }

    private fun fail(message: AgentMessage, error: Exception): Result {
        val reason = error.message?.takeIf { error !is IOException } ?: "Download failed"
        repository.markDownloadFailed(messageId, reason)
        Notifications.showDownloadResult(applicationContext, message.copy(attachmentStatus = "failed", attachmentError = reason))
        return Result.failure()
    }

    private fun foregroundInfo(bytesWritten: Long): ForegroundInfo {
        val message = repository.find(messageId)
        val notification = Notifications.downloadProgress(applicationContext, messageId, message?.attachmentName, bytesWritten, message?.attachmentSize)
        val notificationId = Notifications.downloadNotificationId(messageId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val KEY_MESSAGE_ID = "message_id"
        private const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MS = 500L

        private fun workName(messageId: Long) = "attachment-download-$messageId"

        fun enqueue(context: Context, messageId: Long) {
            val request = OneTimeWorkRequestBuilder<AttachmentDownloadWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(messageId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, messageId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(messageId))
        }

        /** Blocking; call off the main thread. */
        fun isPending(context: Context, messageId: Long): Boolean =
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName(messageId)).get()
                .any { !it.state.isFinished }
    }
}
