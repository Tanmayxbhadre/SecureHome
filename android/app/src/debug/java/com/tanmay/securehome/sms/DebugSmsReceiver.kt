package com.tanmay.securehome.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * adb shell am broadcast -a com.tanmay.securehome.DEBUG_SMS -n com.tanmay.securehome/.sms.DebugSmsReceiver --es from VM-HDFCBK --es body "..."
 * Feeds the same path as a real SMS. Debug builds only.
 */
class DebugSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra("from") ?: "VM-TESTER"
        val body = intent.getStringExtra("body") ?: return
        val pending = goAsync()
        thread {
            try {
                Sharing.onSms(context.applicationContext, from, body, System.currentTimeMillis())
            } finally {
                pending.finish()
            }
        }
    }
}
