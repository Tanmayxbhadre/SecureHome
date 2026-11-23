package com.tanmay.securehome.core

import org.json.JSONObject

/**
 * The joining side of pairing, for a phone (PROTOCOL.md "Pairing", steps 1, 3 and 5).
 * Show [qr], call [poll] until it returns an [Offer], show its SAS, then call [confirmed]
 * until the scanning phone has registered us.
 */
class Joining(relayUrl: String) {
    private val relay = Relay(relayUrl)
    val keys: Keys = Keys.generate()
    val pid: String = Crypto.randomId()
    val startedAt = System.currentTimeMillis()
    val qr: String = Crypto.pairingQr(Crypto.PairingCode(pid, keys.encPubB64, keys.sigPubB64, "phone"))

    class Offer(val sas: String, val name: String, val pairedByName: String, internal val member: Member)

    /** Null until a phone has answered the code. Throws if the answer doesn't check out. */
    fun poll(): Offer? {
        val r = relay.call("GET", "/api/pair/$pid")
        if (r.status == 204) return null
        relay.expect(r, 200)
        val env = r.json().getJSONObject("env")
        val aad = Crypto.pairAad(pid)
        val opened = Crypto.open(env, aad, keys.encPriv, Crypto.INFO_PAIR)
        val b = opened.payload

        val roster = Crypto.verifyRoster(b.getJSONArray("roster"), b.getString("founderSigPub"))
        val pairer = roster[b.getString("pairedBy")]
        require(pairer != null && pairer.kind == "phone") { "That reply didn't come from a phone in the household." }
        require(Crypto.verifyEnvelope(env, aad, Crypto.importPub(pairer.sigPub))) { "That reply's signature is invalid." }
        // The roster must list us, with our own keys, as a phone.
        val self = roster[b.getString("deviceId")]
        require(self != null && self.encPub == keys.encPubB64 && self.sigPub == keys.sigPubB64 && self.kind == "phone") {
            "The household listed this phone with the wrong keys."
        }

        val member = Member(
            relay = relay.base, hid = b.getString("hid"), id = self.id, token = b.getString("token"), name = self.name,
            founderSigPub = b.getString("founderSigPub"), householdKey = b.getString("householdKey"), keys = keys,
        )
        return Offer(opened.sas, self.name, pairer.name, member)
    }

    /** The member once the other phone has confirmed, or null while it hasn't yet. */
    fun confirmed(offer: Offer): Member? {
        val r = relay.call("GET", "/api/h/${offer.member.hid}/me", token = offer.member.token)
        return when (r.status) {
            200 -> offer.member
            401 -> null
            else -> throw RelayException(r.status, "HTTP ${r.status}")
        }
    }

    val expiresAt get() = startedAt + 5 * 60_000L
}

/** A code another device shared with this phone, verified and decrypted. */
data class Received(
    val key: String, // "<fromId>:<relay item id>", unique per item
    val fromId: String,
    val fromName: String,
    val storedAt: Long,
    val kind: String,
    val code: String,
    val sender: String,
    val smsFrom: String,
    val body: String,
    val receivedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("fromId", fromId).put("fromName", fromName).put("storedAt", storedAt).put("kind", kind)
        .put("code", code).put("sender", sender).put("smsFrom", smsFrom).put("body", body).put("receivedAt", receivedAt)

    companion object {
        fun fromJson(j: JSONObject) = Received(
            j.getString("key"), j.getString("fromId"), j.getString("fromName"), j.getLong("storedAt"), j.getString("kind"),
            j.getString("code"), j.getString("sender"), j.optString("smsFrom"), j.optString("body"), j.optLong("receivedAt"),
        )
    }
}
