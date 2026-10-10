package com.freaklair.hermesgadget

// Host-side driver callbacks invoked by the core (native thread). The engine
// plugs in real drivers; unset drivers degrade gracefully.
class NativeHost {
    @Volatile var transport: TransportDriver? = null
    @Volatile var display: DisplayDriver? = null
    @Volatile var mic: MicDriver? = null
    @Volatile var speaker: SpeakerDriver? = null
    @Volatile var storage: StorageDriver? = null

    fun onTransportConnect(url: String, subprotocol: String) {
        transport?.connect(url, subprotocol)
    }
    fun onTransportSend(data: ByteArray): Boolean = transport?.send(data) ?: false
    fun onTransportSendText(text: String): Boolean = transport?.sendText(text) ?: false
    fun onTransportClose() { transport?.close() }

    fun onDisplayFlush(y0: Int, y1: Int) { display?.flush(y0, y1) }

    fun onMicStart(rate: Int): Boolean = mic?.start(rate) ?: false
    fun onMicStop() { mic?.stop() }

    fun onSpeakerBegin(rate: Int): Boolean = speaker?.begin(rate) ?: false
    fun onSpeakerWrite(samples: ShortArray?) {
        if (samples == null) speaker?.end() else speaker?.write(samples)
    }
    fun onSpeakerAbort() { speaker?.abort() }
    fun onSpeakerBusy(): Boolean = speaker?.busy() ?: false
    fun onSpeakerVolume(percent: Int) { speaker?.setVolume(percent) }

    fun onStorageGet(key: String): String? = storage?.get(key)
    fun onStorageSet(key: String, value: String) { storage?.set(key, value) }
    fun onStorageErase(key: String) { storage?.erase(key) }

    companion object {
        @JvmStatic
        var INSTANCE: NativeHost? = null
    }
}

interface TransportDriver {
    fun connect(url: String, subprotocol: String)
    fun send(data: ByteArray): Boolean
    // Core JSON frames must go out as WebSocket text frames, not binary.
    fun sendText(text: String): Boolean
    fun close()
}
interface DisplayDriver { fun flush(y0: Int, y1: Int) }
interface MicDriver {
    fun start(rate: Int): Boolean
    fun stop()
}
interface SpeakerDriver {
    fun begin(rate: Int): Boolean
    fun write(samples: ShortArray)
    fun end()
    fun abort()
    fun busy(): Boolean
    fun setVolume(percent: Int)
}
interface StorageDriver {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun erase(key: String)
}
