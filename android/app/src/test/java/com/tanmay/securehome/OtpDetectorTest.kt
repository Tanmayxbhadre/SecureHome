package com.tanmay.securehome

import com.tanmay.securehome.core.OtpDetector
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the shared fixtures that the JavaScript reference detector also passes. */
class OtpDetectorTest {
    private val root = File(System.getProperty("securehome.root") ?: System.getProperty("taar.root") ?: "../..")

    @Test
    fun sharedFixtures() {
        val cases = JSONArray(File(root, "fixtures/otp-sms.json").readText())
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = OtpDetector.detect(c.getString("from"), c.getString("body"))
            val expect = c.opt("expect")
            val want = if (expect == null || expect == org.json.JSONObject.NULL) null else (expect as org.json.JSONObject).let {
                OtpDetector.Detected(it.getString("code"), it.getString("kind"), it.getString("sender"))
            }
            if (got != want) failures += "#$i ${c.getString("from")}: expected $want, got $got"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
        println("OTP fixtures: ${cases.length()} passed")
    }

    @Test
    fun senderNormalisation() {
        assertEquals("HDFCBK", OtpDetector.normalizeSender("AX-HDFCBK-S"))
        assertEquals("HDFCBK", OtpDetector.normalizeSender("vm-hdfcbk"))
        assertEquals("+919800000000", OtpDetector.normalizeSender(" +919800000000 "))
    }

    @Test
    fun ignoresNonAsciiDigits() {
        assertEquals(null, OtpDetector.detect("AX-TEST", "Your OTP is ४८२१९३"))
    }
}
