package com.tanmay.securehome.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tanmay.securehome.BuildConfig
import com.tanmay.securehome.core.Crypto
import com.tanmay.securehome.core.Household
import com.tanmay.securehome.core.Joining
import com.tanmay.securehome.core.Live
import com.tanmay.securehome.core.OtpDetector
import com.tanmay.securehome.core.Received
import com.tanmay.securehome.core.RelayException
import com.tanmay.securehome.core.otpPayload
import com.tanmay.securehome.data.Recent
import com.tanmay.securehome.data.Store
import com.tanmay.securehome.sms.Sharing
import com.tanmay.securehome.sms.SmsInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface PairStep {
    data object Start : PairStep
    data class Naming(val code: Crypto.PairingCode) : PairStep
    data object Working : PairStep
    data class Check(val pending: Household.PendingPair) : PairStep
    data class Done(val name: String) : PairStep
}

/** The joining phone's side of pairing. */
sealed interface JoinStep {
    data class Showing(val qr: String, val expiresAt: Long) : JoinStep
    data class Check(val sas: String, val name: String, val pairedBy: String) : JoinStep
}

/** Get OTP: live for a few minutes, then stops. */
data class LiveState(val startedAt: Long, val until: Long, val status: String) // connecting | live | stopped | error

enum class Screen { Home, Devices, Pair, Join, GetOtp }

const val GET_OTP_BACK_MS = 5 * 60_000L
const val GET_OTP_LIVE_MS = 5 * 60_000L

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val member = Store.member
    val recent = Store.recent
    val received = Store.received

    var screen by mutableStateOf(Screen.Home)
    var devices by mutableStateOf<List<Crypto.Device>>(emptyList())
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var removed by mutableStateOf(false)
    var pair by mutableStateOf<PairStep>(PairStep.Start)
    var join by mutableStateOf<JoinStep?>(null)
    var live by mutableStateOf<LiveState?>(null)
    /** Codes that arrived while Get OTP was open, to highlight them. */
    var fresh by mutableStateOf<Set<String>>(emptySet())
    /** Per-device auto-share switches, mirrored from Store so the UI recomposes. */
    val auto = mutableStateMapOf<String, Boolean>()

    private var joinJob: Job? = null
    private var liveJob: Job? = null
    private var liveConn: Live? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            Store.load(ctx)
            if (Store.startedFresh) message = "SecureHome couldn't read its saved data, so this phone needs setting up again."
            refresh()
        }
    }

    private fun household() = Store.member.value?.let { Household(it) }

    private fun friendly(e: Throwable): String = when {
        e is RelayException && e.status == 401 -> "This phone is no longer part of the household."
        e is RelayException -> e.message ?: "The relay refused that."
        e is IOException -> "Couldn't reach the relay. Check your connection."
        e is IllegalArgumentException || e is SecurityException -> e.message ?: "That didn't look right."
        else -> e.message ?: "Something went wrong."
    }

    /** Runs [block] on IO with a spinner; shows errors in the snackbar. */
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                if (e is RelayException && e.status == 401) removed = true
                message = friendly(e)
                if (pair == PairStep.Working) pair = PairStep.Start
            } finally {
                busy = false
            }
        }
    }

    private fun showDevices(list: List<Crypto.Device>) {
        devices = list
        list.forEach { auto[it.id] = Store.autoTo(ctx, it.id) }
    }

    /** Roster, codes shared with this phone, and codes in the SMS inbox. Quiet on network errors. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Store.mergeHistory(ctx, SmsInbox.scan(ctx)) }
            val h = household() ?: return@launch
            try {
                showDevices(h.roster())
                Store.addReceived(ctx, h.inbox(0))
                removed = false
            } catch (e: RelayException) {
                if (e.status == 401) removed = true
            } catch (_: IOException) {
                // offline; keep what we have
            }
        }
    }

    // ---------- setup ----------

    fun found(name: String, relay: String, foundingCode: String) = work {
        val m = Household.found(relay, name.trim().ifEmpty { "My phone" }, foundingCode)
        Store.setMember(ctx, m)
        showDevices(Household(m).roster())
    }

    /** Show a QR code until a phone in the household answers it and confirms. */
    fun startJoin(relay: String) {
        joinJob?.cancel()
        screen = Screen.Join
        joinJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val j = Joining(relay.ifBlank { BuildConfig.DEFAULT_RELAY }.trim())
                    join = JoinStep.Showing(j.qr, j.expiresAt)
                    var offer: Joining.Offer? = null
                    while (isActive && offer == null && System.currentTimeMillis() < j.expiresAt) {
                        offer = try {
                            j.poll()
                        } catch (e: IOException) {
                            null // keep trying
                        }
                        if (offer == null) delay(1500)
                    }
                    if (offer == null) continue // expired: show a fresh code
                    join = JoinStep.Check(offer.sas, offer.name, offer.pairedByName)
                    val end = System.currentTimeMillis() + 3 * 60_000
                    while (isActive && System.currentTimeMillis() < end) {
                        val m = runCatching { j.confirmed(offer) }.getOrNull()
                        if (m != null) {
                            Store.setMember(ctx, m)
                            join = null
                            screen = Screen.Home
                            refresh()
                            message = "Joined as ${m.name}."
                            return@launch
                        }
                        delay(1500)
                    }
                    message = "The other phone didn't confirm. Try again."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = friendly(e)
                join = null
                screen = Screen.Home
            }
        }
    }

    fun cancelJoin() {
        joinJob?.cancel()
        join = null
        screen = Screen.Home
    }

    // ---------- sharing ----------

    fun setAutoTo(deviceId: String, on: Boolean) {
        auto[deviceId] = on
        Store.setAutoTo(ctx, deviceId, on)
    }

    fun shareTo(r: Recent, ids: Set<String>) {
        if (ids.isEmpty()) return
        Store.updateRecent(ctx, r.id) { it.copy(status = "sending", detail = "") }
        Sharing.enqueue(ctx, r.id, "manual", ids)
    }

    fun sendTest() = work {
        val h = household() ?: return@work
        val code = (100000..999999).random().toString()
        val body = "SecureHome test: $code is a test code from ${h.me.name}. Nothing to do."
        val sent = h.sendToAll(otpPayload(OtpDetector.Detected(code, "otp", "SECUREHOME"), "SECUREHOME", body, System.currentTimeMillis(), "manual"))
        message = if (sent.isEmpty()) "No other devices are paired yet." else "Test code $code sent to ${sent.joinToString { it.name }}."
    }

    // ---------- Get OTP ----------

    fun startGetOtp() {
        stopLive()
        val h = household() ?: return
        val now = System.currentTimeMillis()
        live = LiveState(now, now + GET_OTP_LIVE_MS, "connecting")
        fresh = emptySet()
        screen = Screen.GetOtp
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            val listener = object : Live.Listener {
                override fun onReady(items: List<Received>) {
                    Store.addReceived(ctx, items)
                    live = live?.copy(status = "live")
                }

                override fun onItem(item: Received) {
                    if (Store.addReceived(ctx, listOf(item)).isNotEmpty()) fresh = fresh + item.key
                }

                override fun onClosed(code: Int) {
                    if (code == 4001) removed = true
                    live = live?.copy(status = "error")
                }

                override fun onFailure(error: Throwable) {
                    live = live?.copy(status = "error")
                }
            }
            try {
                val conn = Live(h, now - GET_OTP_BACK_MS, listener)
                liveConn = conn
                conn.start()
                while (isActive && System.currentTimeMillis() < now + GET_OTP_LIVE_MS) {
                    delay(25_000)
                    conn.ping()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is RelayException && e.status == 401) removed = true
                live = live?.copy(status = "error")
                message = friendly(e)
            } finally {
                liveConn?.stop()
                liveConn = null
                if (live?.status != "error") live = live?.copy(status = "stopped")
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        liveConn?.stop()
        liveConn = null
    }

    fun leaveGetOtp() {
        stopLive()
        live = null
        screen = Screen.Home
    }

    override fun onCleared() {
        stopLive()
        joinJob?.cancel()
    }

    // ---------- pairing (this phone scans) ----------

    fun startPairing() {
        pair = PairStep.Start
        screen = Screen.Pair
    }

    fun scanned(text: String) {
        try {
            pair = PairStep.Naming(Crypto.parsePairingQr(text))
        } catch (e: Exception) {
            message = friendly(e)
        }
    }

    fun answer(code: Crypto.PairingCode, name: String) {
        val h = household() ?: return
        val clean = name.trim().take(60)
        if (clean.isEmpty()) {
            message = "Give the device a name."
            return
        }
        if (devices.any { it.encPub == code.encPub }) {
            message = "That device is already paired."
            return
        }
        pair = PairStep.Working
        work { pair = PairStep.Check(h.answerPairing(code, clean)) }
    }

    fun confirm(p: Household.PendingPair) {
        val h = household() ?: return
        pair = PairStep.Working
        work {
            h.confirmPairing(p)
            showDevices(h.roster())
            pair = PairStep.Done(p.name)
        }
    }

    fun cancelPairing() {
        pair = PairStep.Start
        screen = Screen.Devices
    }

    // ---------- devices ----------

    fun remove(d: Crypto.Device) = work {
        val h = household() ?: return@work
        h.remove(d.id)
        showDevices(h.roster())
        message = "Removed ${d.name}."
    }

    /** Remove this phone from the household and forget its keys. */
    fun leave() = work {
        val h = household()
        try {
            h?.remove(h.me.id)
        } catch (e: RelayException) {
            if (e.status != 401) throw e
        }
        Store.setMember(ctx, null)
        devices = emptyList()
        auto.clear()
        removed = false
        screen = Screen.Home
    }

    fun forgetLocally() {
        viewModelScope.launch(Dispatchers.IO) {
            Store.setMember(ctx, null)
            devices = emptyList()
            auto.clear()
            removed = false
            screen = Screen.Home
        }
    }
}
