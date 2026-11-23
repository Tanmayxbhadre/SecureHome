package com.tanmay.securehome.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tanmay.securehome.core.Household
import com.tanmay.securehome.core.OtpDetector
import com.tanmay.securehome.core.RelayException
import com.tanmay.securehome.core.otpPayload
import com.tanmay.securehome.data.Recent
import com.tanmay.securehome.data.Store
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "SecureHome"

/** A queued share older than this isn't worth delivering any more (the relay keeps items 15 minutes). */
private const val STALE_MS = 14 * 60_000L

/** Receives every incoming SMS. Only messages that carry a code are kept; nothing else is stored or sent. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives as several parts in one broadcast.
        val messages = parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "" }
            .map { (from, ps) -> Triple(from, ps.joinToString("") { it.displayMessageBody ?: "" }, ps.minOf { it.timestampMillis }) }
        val pending = goAsync()
        thread {
            try {
                messages.forEach { (from, body, at) -> Sharing.onSms(context.applicationContext, from, body, at) }
            } catch (e: Exception) {
                Log.e(TAG, "SMS handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

object Sharing {
    /** A new SMS: keep it if it carries a code, and share it with the devices that get codes automatically. */
    fun onSms(ctx: Context, from: String, body: String, sentAt: Long) {
        val d = OtpDetector.detect(from, body) ?: return
        Log.i(TAG, "OTP SMS received")
        Store.load(ctx)
        Store.member.value ?: return
        val r = Recent(
            id = Recent.idFor(from, body, sentAt), code = d.code, kind = d.kind, sender = d.sender, smsFrom = from, body = body,
            receivedAt = sentAt, status = "sending",
        )
        if (!Store.addRecent(ctx, r)) return // already seen
        // Send straight away: Android lets an app that just received an SMS use the network briefly,
        // even when the phone is idle. If that fails, the background job retries.
        if (!sendNow(ctx, r)) enqueue(ctx, r.id, how = "rule", to = null)
    }

    /** One quick attempt to share [r] with the auto-share devices. True if it's settled (sent or nobody to send to). */
    private fun sendNow(ctx: Context, r: Recent): Boolean {
        val me = Store.member.value ?: return true
        val t0 = System.currentTimeMillis()
        return try {
            val h = Household(me, quick = true)
            val roster = h.roster()
            Log.i(TAG, "quick send: roster in ${System.currentTimeMillis() - t0} ms")
            val targets = roster.filter { it.id != me.id && Store.autoTo(ctx, it.id) }.map { it.id }.toSet()
            if (targets.isEmpty()) {
                Store.updateRecent(ctx, r.id) { it.copy(status = "kept", detail = "Not shared automatically") }
                return true
            }
            val sent = h.sendTo(targets, otpPayload(OtpDetector.Detected(r.code, r.kind, r.sender), r.smsFrom, r.body, r.receivedAt, "rule"))
            Store.updateRecent(ctx, r.id) { it.copy(status = "sent", detail = "Sent to " + sent.joinToString(", ") { d -> d.name }) }
            Log.i(TAG, "quick send: sent to ${sent.size} in ${System.currentTimeMillis() - t0} ms")
            true
        } catch (e: Exception) {
            Log.w(TAG, "quick send failed after ${System.currentTimeMillis() - t0} ms, queueing a retry: $e")
            false
        }
    }

    /**
     * Share one recent code, now or as soon as there's a network.
     * [to] = device ids for a manual share; null = the devices with auto-share on (decided when it runs).
     */
    fun enqueue(ctx: Context, recentId: String, how: String, to: Collection<String>?) {
        val req = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(
                workDataOf(
                    "id" to recentId, "how" to how, "queuedAt" to System.currentTimeMillis(),
                    "to" to to?.toTypedArray(),
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("share-$recentId", ExistingWorkPolicy.REPLACE, req)
    }
}

/** Reads codes from the SMS inbox for share-from-history. Needs READ_SMS. */
object SmsInbox {
    fun scan(ctx: Context, sinceMs: Long = Store.HISTORY_MS): List<Recent> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val cutoff = System.currentTimeMillis() - sinceMs
        val out = mutableListOf<Recent>()
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, cols, "${Telephony.Sms.DATE} >= ?", arrayOf(cutoff.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 100) {
                val from = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                val sentAt = c.getLong(3).takeIf { it > 0 } ?: c.getLong(2)
                val d = OtpDetector.detect(from, body) ?: continue
                out += Recent(
                    id = Recent.idFor(from, body, sentAt), code = d.code, kind = d.kind, sender = d.sender, smsFrom = from,
                    body = body, receivedAt = sentAt, status = "kept",
                )
            }
        }
        return out
    }
}

class ShareWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("id") ?: return Result.failure()
        val how = inputData.getString("how") ?: "rule"
        val to = inputData.getStringArray("to")?.toSet()
        val queuedAt = inputData.getLong("queuedAt", System.currentTimeMillis())
        val r = Store.findRecent(ctx, id) ?: return Result.success()
        val me = Store.member.value ?: return Result.failure()
        fun mark(status: String, detail: String) = Store.updateRecent(ctx, id) { it.copy(status = status, detail = detail) }

        if (System.currentTimeMillis() - queuedAt > STALE_MS) {
            mark("expired", "Too old to share")
            return Result.success()
        }
        mark("sending", if (runAttemptCount > 0) "Retrying…" else "")
        return try {
            val h = Household(me)
            val targets = to ?: h.roster().filter { it.id != me.id && Store.autoTo(ctx, it.id) }.map { it.id }.toSet()
            if (targets.isEmpty()) {
                mark("kept", if (how == "rule") "Not shared automatically" else "Not shared")
                return Result.success()
            }
            val detected = OtpDetector.Detected(r.code, r.kind, r.sender)
            val sent = h.sendTo(targets, otpPayload(detected, r.smsFrom, r.body, r.receivedAt, how))
            if (sent.isEmpty()) mark("nobody", "Those devices are no longer paired")
            else mark("sent", "Sent to " + sent.joinToString(", ") { it.name })
            Result.success()
        } catch (e: RelayException) {
            Log.w(TAG, "share failed: ${e.status} ${e.message}")
            if (e.status >= 500 && runAttemptCount < 5) {
                mark("sending", "Relay error, retrying…")
                Result.retry()
            } else {
                mark("failed", if (e.status == 401) "This phone was removed from the household" else e.message ?: "Relay error")
                Result.failure()
            }
        } catch (e: IOException) {
            Log.w(TAG, "share failed", e)
            if (runAttemptCount < 5) {
                mark("sending", "No connection, retrying…")
                Result.retry()
            } else {
                mark("failed", "Couldn't reach the relay")
                Result.failure()
            }
        }
    }
}
