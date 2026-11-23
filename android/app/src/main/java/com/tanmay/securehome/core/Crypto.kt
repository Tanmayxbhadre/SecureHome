package com.tanmay.securehome.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SecureHome protocol v1 crypto. Must stay byte-compatible with relay/public/js/crypto.js; see PROTOCOL.md.
 * Pure JVM (no Android APIs) so it runs in unit tests against the JavaScript vectors.
 */
object Crypto {
    const val INFO_ITEM = "securehome/item/v1"
    const val INFO_PAIR = "securehome/pair/v1"
    const val INFO_SAS = "securehome/sas/v1"
    const val INFO_ENTRY = "securehome/entry/v1"

    private val random = SecureRandom()
    private val ID_RE = Regex("^[A-Za-z0-9_-]{22}$")

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    // ---------- encoding ----------

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun randomId(): String = b64u(randomBytes(16))
    fun randomToken(): String = b64u(randomBytes(32))

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------- keys ----------

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** Uncompressed point 04||X||Y, base64url (87 chars). */
    fun exportPub(key: ECPublicKey): String =
        b64u(byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY))

    fun importPub(s: String): ECPublicKey {
        val raw = unb64u(s)
        require(raw.size == 65 && raw[0] == 4.toByte()) { "bad public key" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256)) as ECPublicKey
    }

    fun privateFromPkcs8(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    /** From a JWK `d` (used by tests). */
    fun privateFromD(d: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), p256))

    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(b.size <= 32)
        return ByteArray(32 - b.size) + b
    }

    // ---------- primitives ----------

    fun ecdh(priv: PrivateKey, pub: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(priv)
            doPhase(pub, true)
            generateSecret()
        }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            // HMAC pads keys with zeros, so an empty salt equals 32 zero bytes (and SecretKeySpec rejects empty keys).
            init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
            doFinal(data)
        }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** Six-digit confirmation code, shown as "123 456". */
    fun sas(shared: ByteArray): String {
        val b = hkdf(shared, ByteArray(0), INFO_SAS, 4)
        val n = BigInteger(1, b).toLong() % 1_000_000
        val s = n.toString().padStart(6, '0')
        return "${s.substring(0, 3)} ${s.substring(3)}"
    }

    // ---------- signatures (P1363 on the wire, DER in Java) ----------

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray =
        derToP1363(Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() })

    fun verify(pub: ECPublicKey, data: ByteArray, sigP1363: ByteArray): Boolean = try {
        sigP1363.size == 64 &&
            Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(p1363ToDer(sigP1363)) }
    } catch (e: Exception) {
        false
    }

    internal fun derToP1363(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte())
        var len = der[i++].toInt() and 0xff
        if (len and 0x80 != 0) i += len and 0x7f // long form (never needed for P-256, but tolerate it)
        fun readInt(): ByteArray {
            require(der[i++] == 0x02.toByte())
            len = der[i++].toInt() and 0xff
            val v = der.copyOfRange(i, i + len)
            i += len
            return fixed32(BigInteger(1, v))
        }
        return readInt() + readInt()
    }

    internal fun p1363ToDer(sig: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var v = b.dropWhile { it == 0.toByte() }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(sig.copyOfRange(0, 32)) + int(sig.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    // ---------- envelopes ----------

    class Sealed(val env: JSONObject, val sas: String)
    class Opened(val payload: JSONObject, val sas: String)

    private fun signedBytes(aad: String, env: JSONObject): ByteArray =
        aad.toByteArray() + unb64u(env.getString("epk")) + unb64u(env.getString("iv")) + unb64u(env.getString("ct"))

    fun seal(recipientEncPub: ECPublicKey, signer: PrivateKey, info: String, aad: String, payload: JSONObject): Sealed {
        val eph = generateKeyPair()
        val shared = ecdh(eph.private, recipientEncPub)
        val epk = unb64u(exportPub(eph.public as ECPublicKey))
        val key = hkdf(shared, epk, info, 32)
        val iv = randomBytes(12)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad.toByteArray())
            doFinal(payload.toString().toByteArray())
        }
        val env = JSONObject().put("v", 1).put("epk", b64u(epk)).put("iv", b64u(iv)).put("ct", b64u(ct))
        env.put("sig", b64u(sign(signer, signedBytes(aad, env))))
        return Sealed(env, sas(shared))
    }

    fun verifyEnvelope(env: JSONObject, aad: String, signerSigPub: ECPublicKey): Boolean = try {
        verify(signerSigPub, signedBytes(aad, env), unb64u(env.getString("sig")))
    } catch (e: Exception) {
        false
    }

    /** Decrypts only. Call [verifyEnvelope] as well before trusting the content. */
    fun open(env: JSONObject, aad: String, encPriv: PrivateKey, info: String): Opened {
        require(env.optInt("v") == 1) { "unsupported envelope" }
        val epk = unb64u(env.getString("epk"))
        val shared = ecdh(encPriv, importPub(b64u(epk)))
        val key = hkdf(shared, epk, info, 32)
        val pt = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64u(env.getString("iv"))))
            updateAAD(aad.toByteArray())
            doFinal(unb64u(env.getString("ct")))
        }
        return Opened(JSONObject(String(pt)), sas(shared))
    }

    fun itemAad(hid: String, from: String, to: String) = "$INFO_ITEM|$hid|$from|$to"
    fun pairAad(pid: String) = "$INFO_PAIR|$pid"

    // ---------- roster ----------

    data class Device(
        val id: String,
        val name: String,
        val kind: String,
        val encPub: String,
        val sigPub: String,
        val addedBy: String,
        val addedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("v", 1).put("id", id).put("name", name).put("kind", kind)
            .put("encPub", encPub).put("sigPub", sigPub).put("addedBy", addedBy).put("addedAt", addedAt)

        companion object {
            fun fromJson(b: JSONObject) = Device(
                b.getString("id"), b.getString("name"), b.getString("kind"),
                b.getString("encPub"), b.getString("sigPub"), b.getString("addedBy"), b.getLong("addedAt"),
            )
        }
    }

    fun signEntry(body: JSONObject, signer: PrivateKey): JSONObject {
        val bodyBytes = body.toString().toByteArray()
        val sig = sign(signer, "$INFO_ENTRY|".toByteArray() + bodyBytes)
        return JSONObject().put("body", b64u(bodyBytes)).put("sig", b64u(sig))
    }

    fun decodeEntry(entry: JSONObject): JSONObject = JSONObject(String(unb64u(entry.getString("body"))))

    private fun verifyEntry(entry: JSONObject, signerSigPub: String): Boolean = try {
        verify(
            importPub(signerSigPub),
            "$INFO_ENTRY|".toByteArray() + unb64u(entry.getString("body")),
            unb64u(entry.getString("sig")),
        )
    } catch (e: Exception) {
        false
    }

    /** Devices whose entries chain back to the founder through phones, in roster order. Others are dropped. */
    fun verifyRoster(entries: JSONArray, founderSigPub: String): LinkedHashMap<String, Device> {
        val parsed = (0 until entries.length()).mapNotNull { i ->
            val e = entries.getJSONObject(i)
            runCatching { e to Device.fromJson(decodeEntry(e)) }.getOrNull()
        }
        val trusted = LinkedHashMap<String, Device>()
        val founder = parsed.firstOrNull { (_, b) -> b.addedBy == b.id && b.sigPub == founderSigPub } ?: return trusted
        if (!verifyEntry(founder.first, founderSigPub)) return trusted
        trusted[founder.second.id] = founder.second
        var grew = true
        while (grew) {
            grew = false
            for ((e, b) in parsed) {
                if (b.id in trusted) continue
                val signer = trusted[b.addedBy] ?: continue
                if (signer.kind != "phone") continue
                if (verifyEntry(e, signer.sigPub)) {
                    trusted[b.id] = b
                    grew = true
                }
            }
        }
        // Keep the relay's order (oldest first) rather than discovery order.
        return LinkedHashMap(parsed.mapNotNull { (_, b) -> trusted[b.id]?.let { b.id to it } }.toMap())
    }

    // ---------- pairing QR ----------

    data class PairingCode(val pid: String, val encPub: String, val sigPub: String, val kind: String)

    fun pairingQr(c: PairingCode) = "securehome:1:${c.pid}:${c.encPub}:${c.sigPub}:${c.kind}"

    fun parsePairingQr(text: String): PairingCode {
        val p = text.trim().split(":")
        require(p.size == 6 && p[0] == "securehome" && p[1] == "1") { "That isn't a SecureHome pairing code." }
        require(ID_RE.matches(p[2])) { "That pairing code is damaged." }
        require(p[5] == "phone" || p[5] == "laptop") { "That pairing code is for an unknown kind of device." }
        importPub(p[3])
        importPub(p[4])
        return PairingCode(p[2], p[3], p[4], p[5])
    }
}
