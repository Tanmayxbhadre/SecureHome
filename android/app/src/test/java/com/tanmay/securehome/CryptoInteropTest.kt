package com.tanmay.securehome

import com.tanmay.securehome.core.Crypto
import com.tanmay.securehome.core.Keys
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JS → Kotlin: opens and verifies what relay/public/js/crypto.js produced (fixtures/crypto-vectors.json).
 * Kotlin → JS: writes build/kotlin-vectors.json, which `node scripts/vectors.mjs --check` verifies.
 */
private fun JSONObject.asMap(): Map<String, Any?> = keys().asSequence().associateWith { get(it) }

class CryptoInteropTest {
    private val root = File(System.getProperty("securehome.root") ?: System.getProperty("taar.root") ?: "../..")
    private val fx = JSONObject(File(root, "fixtures/crypto-vectors.json").readText())

    @Test
    fun opensJavascriptEnvelope() {
        val e = fx.getJSONObject("envelope")
        val env = e.getJSONObject("env")
        val aad = e.getString("aad")
        val signer = Crypto.importPub(e.getString("signerSigPub"))
        assertTrue("signature", Crypto.verifyEnvelope(env, aad, signer))
        val priv = Crypto.privateFromD(Crypto.unb64u(fx.getJSONObject("recipient").getString("encPrivD")))
        val opened = Crypto.open(env, aad, priv, e.getString("info"))
        assertEquals(e.getJSONObject("payload").asMap(), opened.payload.asMap())
        assertEquals(e.getString("sas"), opened.sas)

        // Any change to the authenticated data must break the signature.
        assertFalse(Crypto.verifyEnvelope(env, aad + "x", signer))
        val tampered = JSONObject(env.toString()).put("ct", Crypto.b64u(Crypto.unb64u(env.getString("ct")).also { it[0] = (it[0] + 1).toByte() }))
        assertFalse(Crypto.verifyEnvelope(tampered, aad, signer))
    }

    @Test
    fun verifiesJavascriptRoster() {
        val r = fx.getJSONObject("roster")
        val got = Crypto.verifyRoster(r.getJSONArray("entries"), r.getString("founderSigPub")).keys.toList()
        val want = r.getJSONArray("expectIds").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(want, got)
        assertTrue(Crypto.verifyRoster(r.getJSONArray("entries"), Keys.generate().sigPubB64).isEmpty())
    }

    @Test
    fun sha256Matches() {
        val s = fx.getJSONObject("sha256")
        assertEquals(s.getString("hex"), Crypto.sha256Hex(s.getString("input")))
    }

    @Test
    fun signatureFormatRoundTrips() {
        val k = Keys.generate()
        repeat(200) {
            val data = Crypto.randomBytes(40)
            val sig = Crypto.sign(k.sigPriv, data)
            assertEquals(64, sig.size)
            assertTrue(Crypto.verify(k.sigPub, data, sig))
        }
        val pub = k.encPubB64
        assertEquals(87, pub.length)
        assertArrayEquals(Crypto.unb64u(pub), Crypto.unb64u(Crypto.exportPub(Crypto.importPub(pub))))
    }

    @Test
    fun writesVectorsForJavascript() {
        val recipient = Crypto.importPub(fx.getJSONObject("recipient").getString("encPub"))
        val founder = Keys.generate()
        val laptop = Keys.generate()
        val aad = Crypto.itemAad("H".repeat(22), "F".repeat(22), "T".repeat(22))
        val payload = JSONObject().put("v", 1).put("kind", "delivery").put("code", "4821")
            .put("body", "Share OTP 4821 ₹ ✓ \"q\" </b>").put("receivedAt", 1795000000000L)
        val sealed = Crypto.seal(recipient, founder.sigPriv, Crypto.INFO_ITEM, aad, payload)

        val fid = Crypto.randomId()
        val lid = Crypto.randomId()
        val fe = Crypto.signEntry(Crypto.Device(fid, "Kotlin phone", "phone", founder.encPubB64, founder.sigPubB64, fid, 1).toJson(), founder.sigPriv)
        val le = Crypto.signEntry(Crypto.Device(lid, "Laptop", "laptop", laptop.encPubB64, laptop.sigPubB64, fid, 2).toJson(), founder.sigPriv)
        val out = JSONObject()
            .put("info", Crypto.INFO_ITEM).put("aad", aad).put("env", sealed.env).put("sas", sealed.sas)
            .put("payload", payload).put("signerSigPub", founder.sigPubB64)
            .put("roster", JSONObject().put("founderSigPub", founder.sigPubB64).put("entries", JSONArray().put(fe).put(le)).put("expectIds", JSONArray().put(fid).put(lid)))
        File("build").mkdirs()
        File("build/kotlin-vectors.json").writeText(out.toString(2))

        // And Kotlin can read its own output.
        assertEquals(listOf(fid, lid), Crypto.verifyRoster(JSONArray().put(fe).put(le), founder.sigPubB64).keys.toList())
    }

    @Test
    fun parsesPairingCodes() {
        val k = Keys.generate()
        val pid = Crypto.randomId()
        val qr = Crypto.pairingQr(Crypto.PairingCode(pid, k.encPubB64, k.sigPubB64, "laptop"))
        assertEquals(pid, Crypto.parsePairingQr("  $qr\n").pid)
        assertTrue(qr.startsWith("securehome:1:"))
        assertTrue(runCatching { Crypto.parsePairingQr(qr.replaceFirst("securehome:", "hhl:")) }.isFailure)
        assertTrue(runCatching { Crypto.parsePairingQr("https://example.com") }.isFailure)
        assertTrue(runCatching { Crypto.parsePairingQr(qr.replace("laptop", "toaster")) }.isFailure)
    }
}
