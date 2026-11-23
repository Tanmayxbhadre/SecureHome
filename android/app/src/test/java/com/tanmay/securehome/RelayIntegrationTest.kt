package com.tanmay.securehome

import com.tanmay.securehome.core.Crypto
import com.tanmay.securehome.core.Household
import com.tanmay.securehome.core.Joining
import com.tanmay.securehome.core.Live
import com.tanmay.securehome.core.Received
import com.tanmay.securehome.core.Keys
import com.tanmay.securehome.core.RelayException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's relay client against a live local relay. Skipped unless SECUREHOME_RELAY is set:
 *   SECUREHOME_RELAY=http://127.0.0.1:8787 ./gradlew testDebugUnitTest --tests '*RelayIntegrationTest*'
 */
class RelayIntegrationTest {
    private val relay = System.getenv("SECUREHOME_RELAY") ?: System.getenv("TAAR_RELAY")
    private val code = System.getenv("SECUREHOME_FOUNDING_CODE") ?: System.getenv("TAAR_FOUNDING_CODE") ?: "local-dev-founding-code"

    @Test
    fun foundPairSendLeave() {
        assumeTrue("set SECUREHOME_RELAY to run", relay != null)

        val wrong = runCatching { Household.found(relay!!, "Kotlin phone", "wrong") }.exceptionOrNull()
        assertEquals(403, (wrong as RelayException).status)

        val h = Household(Household.found(relay!!, "Kotlin phone", code))
        val laptop = Keys.generate()
        val qr = Crypto.PairingCode(Crypto.randomId(), laptop.encPubB64, laptop.sigPubB64, "laptop")
        val pending = h.answerPairing(qr, "Kotlin laptop") // authenticated PUT /api/pair
        h.confirmPairing(pending)
        assertEquals(listOf("Kotlin phone", "Kotlin laptop"), h.roster().map { it.name })

        val again = runCatching { h.answerPairing(qr, "Again") }.exceptionOrNull()
        assertEquals(409, (again as RelayException).status)

        assertEquals(listOf("Kotlin laptop"), h.sendToAll(JSONObject().put("v", 1).put("code", "1234")).map { it.name })
        h.roster().filter { it.id != h.me.id }.forEach { h.remove(it.id) }
        h.remove(h.me.id)
        assertEquals(401, (runCatching { h.roster() }.exceptionOrNull() as RelayException).status)
    }

    /** A second phone joins by QR, both phones share with each other, and Get OTP's live connection works. */
    @Test
    fun secondPhoneJoinsAndSharesBothWays() {
        assumeTrue("set SECUREHOME_RELAY to run", relay != null)
        val mine = Household(Household.found(relay!!, "My phone", code))

        val joining = Joining(relay)
        assertNull(joining.poll()) // nobody has answered yet
        val pending = mine.answerPairing(Crypto.parsePairingQr(joining.qr), "Second phone")
        val offer = joining.poll()!!
        assertEquals(pending.sas, offer.sas) // both screens show the same code
        assertEquals("Second phone", offer.name)
        assertEquals("My phone", offer.pairedByName)
        assertNull(joining.confirmed(offer)) // not until my phone confirms
        mine.confirmPairing(pending)
        val other = Household(joining.confirmed(offer)!!)
        assertEquals(listOf("My phone", "Second phone"), other.roster().map { it.name })

        // Get OTP: the second phone listens live while the first shares a code.
        val got = LinkedBlockingQueue<Received>()
        val ready = LinkedBlockingQueue<Int>()
        val live = Live(other, System.currentTimeMillis() - 5 * 60_000, object : Live.Listener {
            override fun onReady(items: List<Received>) { ready.put(items.size) }
            override fun onItem(item: Received) { got.put(item) }
            override fun onClosed(code: Int) {}
            override fun onFailure(error: Throwable) { error.printStackTrace() }
        })
        live.start()
        assertEquals(0, ready.poll(10, TimeUnit.SECONDS))
        val otherId = other.me.id
        mine.sendTo(setOf(otherId), JSONObject().put("v", 1).put("kind", "delivery").put("code", "4821").put("sender", "SWIGGY"))
        val item = got.poll(10, TimeUnit.SECONDS)!!
        assertEquals("4821", item.code)
        assertEquals("delivery", item.kind)
        assertEquals("My phone", item.fromName)
        live.ping()
        live.stop()

        // Two-way: the second phone shares one back; the first verifies it through its own signature on that phone's entry.
        other.sendTo(setOf(mine.me.id), JSONObject().put("v", 1).put("kind", "otp").put("code", "778899").put("sender", "ICICIT"))
        val mineGot = mine.inbox(0)
        assertEquals(listOf("778899"), mineGot.map { it.code })
        assertEquals("Second phone", mineGot[0].fromName)
        // And the second phone's inbox still has the delivery code for "Shared with me".
        assertTrue(other.inbox(0).any { it.code == "4821" })

        other.remove(other.me.id)
        mine.remove(mine.me.id)
    }
}
