package com.tanmay.securehome.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A live connection to this phone's inbox on the relay (PROTOCOL.md "WebSocket"). Used while Get OTP
 * is open. Callbacks run on OkHttp's thread. Call [ping] every ~30 s and [stop] when done.
 */
class Live(private val h: Household, private val since: Long, private val listener: Listener) {
    interface Listener {
        fun onReady(items: List<Received>)
        fun onItem(item: Received)
        /** [code] 4001 = this phone was removed from the household. */
        fun onClosed(code: Int)
        fun onFailure(error: Throwable)
    }

    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    @Volatile private var roster: Map<String, Crypto.Device> = emptyMap()
    @Volatile private var stopped = false

    /** Blocking (fetches the roster first); call off the main thread. */
    fun start() {
        roster = h.rosterMap()
        val url = h.me.relay.replaceFirst(Regex("^http"), "ws") + "/api/h/${h.me.hid}/ws"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("token", h.me.token).put("since", since).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "ready" -> {
                        val items = msg.optJSONArray("items")
                        listener.onReady((0 until (items?.length() ?: 0)).mapNotNull { open(items!!.getJSONObject(it)) })
                    }
                    "item" -> open(msg.getJSONObject("item"))?.let(listener::onItem)
                    "roster" -> runCatching { roster = h.rosterMap() }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) listener.onClosed(code)
                shutdown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped) listener.onFailure(t)
                shutdown()
            }
        })
    }

    private fun open(item: JSONObject): Received? {
        runCatching { return h.openItem(item, roster) }
        // A device paired since we connected: refresh the roster once and retry.
        return runCatching {
            roster = h.rosterMap()
            h.openItem(item, roster)
        }.getOrNull()
    }

    fun ping() {
        ws?.send("""{"type":"ping"}""")
    }

    fun stop() {
        stopped = true
        ws?.close(1000, "done")
    }

    private fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
