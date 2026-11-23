package com.tanmay.securehome.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey

class RelayException(val status: Int, message: String) : IOException(message)

/** Minimal JSON-over-HTTPS client for the relay API in PROTOCOL.md. Blocking; call off the main thread. */
class Relay(base: String, private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 15_000) {
    val base = base.trim().trimEnd('/')

    class Response(val status: Int, val text: String) {
        fun json() = JSONObject(text)
    }

    fun call(method: String, path: String, body: JSONObject? = null, token: String? = null): Response {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("accept", "application/json")
            token?.let { conn.setRequestProperty("authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(status, text)
        } finally {
            conn.disconnect()
        }
    }

    fun expect(r: Response, vararg ok: Int): Response {
        if (r.status in ok) return r
        val msg = runCatching { r.json().optString("error") }.getOrNull().orEmpty().ifEmpty { "HTTP ${r.status}" }
        throw RelayException(r.status, msg)
    }
}

/** This device's two key pairs. */
class Keys(val encPriv: PrivateKey, val encPub: ECPublicKey, val sigPriv: PrivateKey, val sigPub: ECPublicKey) {
    val encPubB64 get() = Crypto.exportPub(encPub)
    val sigPubB64 get() = Crypto.exportPub(sigPub)

    fun toJson(): JSONObject = JSONObject()
        .put("encPriv", Crypto.b64u(encPriv.encoded)).put("encPub", encPubB64)
        .put("sigPriv", Crypto.b64u(sigPriv.encoded)).put("sigPub", sigPubB64)

    companion object {
        fun generate(): Keys {
            val e = Crypto.generateKeyPair()
            val s = Crypto.generateKeyPair()
            return Keys(e.private, e.public as ECPublicKey, s.private, s.public as ECPublicKey)
        }

        fun fromJson(j: JSONObject) = Keys(
            Crypto.privateFromPkcs8(Crypto.unb64u(j.getString("encPriv"))), Crypto.importPub(j.getString("encPub")),
            Crypto.privateFromPkcs8(Crypto.unb64u(j.getString("sigPriv"))), Crypto.importPub(j.getString("sigPub")),
        )
    }
}

/** Everything a paired phone needs to act for itself in a household. */
data class Member(
    val relay: String,
    val hid: String,
    val id: String,
    val token: String,
    val name: String,
    val founderSigPub: String,
    val householdKey: String,
    val keys: Keys,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1).put("relay", relay).put("hid", hid).put("id", id).put("token", token).put("name", name)
        .put("founderSigPub", founderSigPub).put("householdKey", householdKey).put("keys", keys.toJson())

    companion object {
        fun fromJson(j: JSONObject) = Member(
            j.getString("relay"), j.getString("hid"), j.getString("id"), j.getString("token"), j.getString("name"),
            j.getString("founderSigPub"), j.getString("householdKey"), Keys.fromJson(j.getJSONObject("keys")),
        )
    }
}

/**
 * Household operations for a phone. All methods block on the network.
 * [quick] uses short timeouts, for work that must finish inside an SMS broadcast.
 */
class Household(val me: Member, quick: Boolean = false) {
    private val relay = if (quick) Relay(me.relay, 4_000, 4_000) else Relay(me.relay)
    private fun h(rest: String) = "/api/h/${me.hid}/$rest"

    companion object {
        /** Start a new household with this phone as its founder. [foundingCode] is the relay's FOUNDING_SECRET. */
        fun found(relayUrl: String, name: String, foundingCode: String): Member {
            val keys = Keys.generate()
            val id = Crypto.randomId()
            val m = Member(
                relay = Relay(relayUrl).base, hid = Crypto.randomId(), id = id, token = Crypto.randomToken(),
                name = name, founderSigPub = keys.sigPubB64, householdKey = Crypto.randomToken(), keys = keys,
            )
            val body = Crypto.Device(id, name, "phone", keys.encPubB64, keys.sigPubB64, id, System.currentTimeMillis())
            val entry = Crypto.signEntry(body.toJson(), keys.sigPriv)
            val relay = Relay(m.relay)
            val r = relay.call(
                "POST", "/api/h/${m.hid}/init",
                JSONObject().put("entry", entry).put("tokenHash", Crypto.sha256Hex(m.token)).put("foundingCode", foundingCode.trim()),
            )
            when (r.status) {
                403 -> throw RelayException(403, "That founding code isn't right.")
                503 -> throw RelayException(503, "This relay isn't set up to create households yet (no founding code is configured).")
            }
            relay.expect(r, 201)
            return m
        }
    }

    private fun rosterEntries(): JSONArray =
        relay.expect(relay.call("GET", h("roster"), token = me.token), 200).json().getJSONArray("entries")

    /** Verified roster, oldest first. Throws RelayException(401) if this phone was removed. */
    fun roster(): List<Crypto.Device> = rosterMap().values.toList()

    fun rosterMap(): Map<String, Crypto.Device> = Crypto.verifyRoster(rosterEntries(), me.founderSigPub)

    class PendingPair(val sas: String, val deviceId: String, val name: String, val kind: String, internal val entry: JSONObject, internal val tokenHash: String)

    /** Step 2 of pairing: answer a scanned QR. Show [PendingPair.sas], then call [confirmPairing] if it matches. */
    fun answerPairing(code: Crypto.PairingCode, name: String): PendingPair {
        val deviceId = Crypto.randomId()
        val token = Crypto.randomToken()
        val body = Crypto.Device(deviceId, name, code.kind, code.encPub, code.sigPub, me.id, System.currentTimeMillis())
        val entry = Crypto.signEntry(body.toJson(), me.keys.sigPriv)
        val entries = rosterEntries().put(entry)
        val bundle = JSONObject()
            .put("v", 1).put("hid", me.hid).put("deviceId", deviceId).put("token", token).put("name", name)
            .put("kind", code.kind).put("founderSigPub", me.founderSigPub).put("pairedBy", me.id)
            .put("roster", entries).put("householdKey", me.householdKey)
        val sealed = Crypto.seal(Crypto.importPub(code.encPub), me.keys.sigPriv, Crypto.INFO_PAIR, Crypto.pairAad(code.pid), bundle)
        val r = relay.call("PUT", "/api/pair/${code.pid}", JSONObject().put("hid", me.hid).put("env", sealed.env), me.token)
        if (r.status == 409) throw RelayException(409, "That code was already used. Show a fresh code on the other device and scan again.")
        relay.expect(r, 201)
        return PendingPair(sealed.sas, deviceId, name, code.kind, entry, Crypto.sha256Hex(token))
    }

    fun confirmPairing(p: PendingPair) {
        relay.expect(relay.call("POST", h("devices"), JSONObject().put("entry", p.entry).put("tokenHash", p.tokenHash), me.token), 201)
    }

    fun remove(deviceId: String) {
        relay.expect(relay.call("DELETE", h("devices/$deviceId"), token = me.token), 204, 404)
    }

    fun send(to: Crypto.Device, payload: JSONObject) {
        val sealed = Crypto.seal(Crypto.importPub(to.encPub), me.keys.sigPriv, Crypto.INFO_ITEM, Crypto.itemAad(me.hid, me.id, to.id), payload)
        relay.expect(relay.call("POST", h("items"), JSONObject().put("to", to.id).put("env", sealed.env), me.token), 201)
    }

    /** Send to every other device in the household. Returns the devices it reached. */
    fun sendToAll(payload: JSONObject): List<Crypto.Device> = sendTo(null, payload)

    /** Send to the listed devices (null = every other device). Unknown or removed ids are skipped. */
    fun sendTo(ids: Collection<String>?, payload: JSONObject): List<Crypto.Device> {
        val targets = roster().filter { it.id != me.id && (ids == null || it.id in ids) }
        targets.forEach { send(it, payload) }
        return targets
    }

    // ---------- receiving ----------

    /** Verify the sender's signature, then decrypt. Throws SecurityException for anything untrusted. */
    fun openItem(item: JSONObject, roster: Map<String, Crypto.Device>): Received {
        val fromId = item.getString("from")
        val from = roster[fromId] ?: throw SecurityException("item from a device that isn't in the household")
        val env = item.getJSONObject("env")
        val aad = Crypto.itemAad(me.hid, fromId, me.id)
        if (!Crypto.verifyEnvelope(env, aad, Crypto.importPub(from.sigPub))) throw SecurityException("bad signature")
        val p = Crypto.open(env, aad, me.keys.encPriv, Crypto.INFO_ITEM).payload
        return Received(
            key = "$fromId:${item.get("id")}", fromId = fromId, fromName = from.name, storedAt = item.getLong("storedAt"),
            kind = p.optString("kind", "otp"), code = p.getString("code"), sender = p.optString("sender"),
            smsFrom = p.optString("smsFrom"), body = p.optString("body"), receivedAt = p.optLong("receivedAt"),
        )
    }

    /** Items shared with this phone since [since] (the relay keeps them about 15 minutes). Bad items are dropped. */
    fun inbox(since: Long = 0): List<Received> {
        val items = relay.expect(relay.call("GET", h("items") + "?since=$since", token = me.token), 200).json().getJSONArray("items")
        if (items.length() == 0) return emptyList()
        val roster = rosterMap()
        return (0 until items.length()).mapNotNull { runCatching { openItem(items.getJSONObject(it), roster) }.getOrNull() }
    }
}

/** The item payload for an SMS (PROTOCOL.md "Item payload"). */
fun otpPayload(d: OtpDetector.Detected, smsFrom: String, body: String, receivedAt: Long, how: String): JSONObject =
    JSONObject()
        .put("v", 1).put("kind", d.kind).put("code", d.code).put("sender", d.sender).put("smsFrom", smsFrom)
        .put("body", body).put("receivedAt", receivedAt).put("sharedAt", System.currentTimeMillis()).put("how", how)
