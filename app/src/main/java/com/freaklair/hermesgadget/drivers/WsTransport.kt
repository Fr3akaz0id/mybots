package com.freaklair.hermesgadget.drivers

import android.util.Log
import com.freaklair.hermesgadget.TransportDriver
import okhttp3.*
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// WebSocket transport mirroring python/sim/transport.py. connect() is async;
// open/message/close callbacks land on OkHttp's thread and go through the
// Engine's queue so the core only ever sees them on its own thread.
class WsTransport(
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onBinary: (ByteArray) -> Unit,
    private val onClose: (String) -> Unit,
) : TransportDriver, WebSocketListener() {

    private val client = OkHttpClient.Builder().pingInterval(10, java.util.concurrent.TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val gen = AtomicLong(0)

    override fun connect(url: String, subprotocol: String) {
        gen.incrementAndGet()
        val myGen = gen.get()
        ws?.cancel()
        connected.set(false)
        val req = Request.Builder().url(url)
        if (subprotocol.isNotEmpty()) req.header("Sec-WebSocket-Protocol", subprotocol)
        Log.i("hgtr", "ws connect $url")
        ws = client.newWebSocket(req.build(), this.also { })
        // capture gen for this connection to ignore stale listener callbacks
        this.pendingGen = myGen
    }
    private var pendingGen = 0L

    override fun send(data: ByteArray): Boolean {
        return try { ws?.send(ByteString.of(*data)) ?: false } catch (e: Exception) { false }
    }

    override fun sendText(text: String): Boolean {
        return try { ws?.send(text) ?: false } catch (e: Exception) { false }
    }

    override fun close() {
        gen.incrementAndGet()
        ws?.close(NORMAL_CLOSE, null)
        ws = null
        connected.set(false)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (webSocket !== ws) return
        connected.set(true)
        Log.i("hgtr", "ws open")
        onOpen()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket !== ws) return
        onText(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (webSocket !== ws) return
        onBinary(bytes.toByteArray())
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket !== ws) return
        connected.set(false)
        onClose(reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (webSocket !== ws) return
        connected.set(false)
        onClose(t.message ?: "connection failed")
    }

    companion object { const val NORMAL_CLOSE = 1000 }
}
