package com.tanmay.securehome.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.edit
import com.tanmay.securehome.core.Crypto
import com.tanmay.securehome.core.Member
import com.tanmay.securehome.core.Received
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Files in app-private storage, encrypted with an AES key that lives in the Android Keystore
 * (hardware-backed where the phone supports it, never exportable).
 */
private object Vault {
    private const val ALIAS = "securehome-vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun file(ctx: Context, name: String) = AtomicFile(File(ctx.noBackupFilesDir, "$name.bin"))

    fun write(ctx: Context, name: String, text: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = c.iv + c.doFinal(text.toByteArray())
        val f = file(ctx, name)
        val out = f.startWrite()
        try {
            out.write(bytes)
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    fun read(ctx: Context, name: String): String? {
        val bytes = try {
            file(ctx, name).readFully()
        } catch (e: FileNotFoundException) {
            return null
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(c.doFinal(bytes.copyOfRange(12, bytes.size)))
    }

    fun delete(ctx: Context, name: String) = file(ctx, name).delete()
}

/** One detected code, as shown in the app's recent list. */
data class Recent(
    val id: String,
    val code: String,
    val kind: String,
    val sender: String,
    val smsFrom: String,
    val body: String,
    val receivedAt: Long,
    val status: String, // sending | sent | nobody | failed | expired | kept (not shared)
    val detail: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("code", code).put("kind", kind).put("sender", sender).put("smsFrom", smsFrom)
        .put("body", body).put("receivedAt", receivedAt).put("status", status).put("detail", detail).put("updatedAt", updatedAt)

    companion object {
        /**
         * Stable id for one SMS, so the copy caught by the receiver and the copy read from the SMS inbox
         * match: the receiver's timestamp is the service-centre time, which the inbox stores as date_sent.
         */
        fun idFor(from: String, body: String, sentAt: Long): String =
            Crypto.sha256Hex("$from|$sentAt|$body").substring(0, 22)

        fun fromJson(j: JSONObject) = Recent(
            j.getString("id"), j.getString("code"), j.getString("kind"), j.getString("sender"), j.getString("smsFrom"),
            j.getString("body"), j.getLong("receivedAt"), j.getString("status"), j.optString("detail"), j.optLong("updatedAt"),
        )
    }
}

/** Process-wide state shared by the UI, the SMS receiver and the share worker. */
object Store {
    private const val MAX_RECENT = 100
    private const val MAX_RECEIVED = 50
    const val HISTORY_MS = 2 * 24 * 60 * 60_000L
    private val lock = Any()
    private var loaded = false

    private val _member = MutableStateFlow<Member?>(null)
    val member: StateFlow<Member?> = _member
    private val _recent = MutableStateFlow<List<Recent>>(emptyList())
    val recent: StateFlow<List<Recent>> = _recent
    private val _received = MutableStateFlow<List<Received>>(emptyList())
    /** Codes other devices shared with this phone, newest first. */
    val received: StateFlow<List<Received>> = _received

    /**
     * True if saved data existed but couldn't be read (for example the Keystore key was lost), so this
     * phone started fresh. The UI tells the user once; the household needs setting up again.
     */
    @Volatile var startedFresh = false
        private set

    fun load(ctx: Context) = synchronized(lock) {
        if (loaded) return
        try {
            _member.value = Vault.read(ctx, "member")?.let { Member.fromJson(JSONObject(it)) }
            _recent.value = Vault.read(ctx, "recent")?.let { s ->
                JSONArray(s).let { a -> (0 until a.length()).map { Recent.fromJson(a.getJSONObject(it)) } }
            } ?: emptyList()
            _received.value = Vault.read(ctx, "received")?.let { s ->
                JSONArray(s).let { a -> (0 until a.length()).map { Received.fromJson(a.getJSONObject(it)) } }
            } ?: emptyList()
        } catch (e: Exception) {
            // Unreadable vault: never crash-loop. Drop it and start as a fresh install.
            android.util.Log.e("SecureHome", "saved data unreadable, starting fresh: ${e.javaClass.simpleName}")
            listOf("member", "recent", "received").forEach { Vault.delete(ctx, it) }
            prefs(ctx).edit { clear() }
            _member.value = null
            _recent.value = emptyList()
            _received.value = emptyList()
            startedFresh = true
        }
        loaded = true
    }

    fun setMember(ctx: Context, m: Member?) = synchronized(lock) {
        load(ctx)
        if (m == null) {
            Vault.delete(ctx, "member")
            Vault.delete(ctx, "recent")
            Vault.delete(ctx, "received")
            _recent.value = emptyList()
            _received.value = emptyList()
            prefs(ctx).edit { clear() }
        } else {
            Vault.write(ctx, "member", m.toJson().toString())
        }
        _member.value = m
    }

    /** Adds [r] unless that SMS is already listed. Returns false if it was. */
    fun addRecent(ctx: Context, r: Recent): Boolean = synchronized(lock) {
        load(ctx)
        if (_recent.value.any { it.id == r.id }) return false
        saveRecent(ctx, (listOf(r) + _recent.value).sortedByDescending { it.receivedAt }.take(MAX_RECENT))
        true
    }

    /** Merge codes found in the SMS inbox (status "kept") and drop anything older than [HISTORY_MS]. */
    fun mergeHistory(ctx: Context, found: List<Recent>) = synchronized(lock) {
        load(ctx)
        val known = _recent.value.map { it.id }.toSet()
        // Same SMS seen by the receiver under a different id (older versions used random ids).
        fun seen(r: Recent) = r.id in known || _recent.value.any {
            it.body == r.body && it.code == r.code && kotlin.math.abs(it.receivedAt - r.receivedAt) < 10 * 60_000
        }
        val cutoff = System.currentTimeMillis() - HISTORY_MS
        val merged = (_recent.value + found.filterNot(::seen)).filter { it.receivedAt >= cutoff }
            .sortedByDescending { it.receivedAt }.take(MAX_RECENT)
        if (merged != _recent.value) saveRecent(ctx, merged)
    }

    /** Adds newly received codes; returns the ones that weren't known yet. */
    fun addReceived(ctx: Context, items: List<Received>): List<Received> = synchronized(lock) {
        load(ctx)
        val known = _received.value.map { it.key }.toSet()
        val fresh = items.filter { it.key !in known }.distinctBy { it.key }
        if (fresh.isNotEmpty()) {
            val list = (fresh + _received.value).sortedByDescending { it.storedAt }.take(MAX_RECEIVED)
            Vault.write(ctx, "received", JSONArray(list.map { it.toJson() }).toString())
            _received.value = list
        }
        fresh
    }

    fun updateRecent(ctx: Context, id: String, f: (Recent) -> Recent) = synchronized(lock) {
        load(ctx)
        saveRecent(ctx, _recent.value.map { if (it.id == id) f(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun findRecent(ctx: Context, id: String): Recent? {
        load(ctx)
        return _recent.value.firstOrNull { it.id == id }
    }

    private fun saveRecent(ctx: Context, list: List<Recent>) {
        Vault.write(ctx, "recent", JSONArray(list.map { it.toJson() }).toString())
        _recent.value = list
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("securehome", Context.MODE_PRIVATE)

    /**
     * Whether this phone's OTPs go to [deviceId] automatically. Defaults: on for the phone that started
     * the household (so the laptop and the other phone get codes), off for phones that joined.
     */
    fun autoTo(ctx: Context, deviceId: String): Boolean {
        val m = member.value ?: return false
        return prefs(ctx).getBoolean("auto.$deviceId", m.founderSigPub == m.keys.sigPubB64)
    }

    fun setAutoTo(ctx: Context, deviceId: String, on: Boolean) = prefs(ctx).edit { putBoolean("auto.$deviceId", on) }
}
