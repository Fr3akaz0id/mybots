package com.freaklair.hermesgadget.drivers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.freaklair.hermesgadget.MicDriver
import com.freaklair.hermesgadget.SpeakerDriver

// ONE AudioRecord client in the whole app (mic exclusivity on Samsung HAL is
// the death of any two-capture design). MicBus owns the physical capture and
// fans 20 ms chunks out to every registered tap:
//  - core taps (one per PageEngine, active only while that engine listens)
//  - the wake-word tap (alive while the app is in the foreground)
// A capture failure retries itself until it opens.
object MicBus {
    private val coreTaps = java.util.concurrent.CopyOnWriteArrayList<(ShortArray) -> Unit>()
    @Volatile private var wakeTap: ((ShortArray) -> Unit)? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var rate = 16000
    private val lock = Object()

    fun registerCore(tap: (ShortArray) -> Unit) { coreTaps.add(tap); ensure() }
    fun unregisterCore(tap: (ShortArray) -> Unit) { coreTaps.remove(tap); maybeRelease() }
    fun setWake(tap: ((ShortArray) -> Unit)?) {
        wakeTap = tap
        if (tap != null) ensure() else maybeRelease()
    }

    private fun needed(): Boolean = coreTaps.isNotEmpty() || wakeTap != null

    // Test hook: synthetic PCM (16k mono s16) fed through the same fan-out at
    // real-time pacing while the physical mic is muted for taps. Wake taps and
    // core taps both see it, so KWS, core VAD and hold-monitoring all behave
    // exactly as with a real mic. One playback at a time.
    @Volatile private var injecting = false
    private var playThread: Thread? = null

    fun inject(samples: ShortArray, sr: Int = 16000, onDone: (() -> Unit)? = null) {
        synchronized(lock) {
            if (playThread?.isAlive == true) return
            injecting = true
            startRate(sr)
            ensure()
            playThread = Thread {
                var off = 0
                val frame = sr / 50 // 20 ms
                while (off < samples.size && injecting) {
                    val t0 = System.nanoTime()
                    val n = minOf(frame, samples.size - off)
                    val piece = samples.copyOfRange(off, off + n)
                    wakeTap?.invoke(piece)
                    for (t in coreTaps) t(piece)
                    off += n
                    val ms = 20L - (System.nanoTime() - t0) / 1_000_000L
                    if (ms > 0) try { Thread.sleep(ms) } catch (_: InterruptedException) { break }
                }
                injecting = false
                onDone?.invoke()
            }.also { it.isDaemon = true; it.start() }
        }
    }

    fun stopInject() { injecting = false }

    private fun ensure() {
        synchronized(lock) {
            if (record != null || !needed()) return
            val r = rate
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min == AudioRecord.ERROR_BAD_VALUE || min == AudioRecord.ERROR) return
            val chunk = maxOf(r / 50, min)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, r,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, chunk * 4)
            } catch (e: SecurityException) {
                return
            } catch (e: IllegalArgumentException) {
                return
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
            rec.startRecording()
            record = rec
            thread = Thread {
                val buf = ShortArray(chunk)
                while (record === rec && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = rec.read(buf, 0, chunk)
                    if (n > 0 && !injecting) {
                        val piece = buf.copyOf(n)
                        wakeTap?.invoke(piece)
                        for (t in coreTaps) t(piece)
                    } else if (n < 0) break
                }
                if (record === rec) { rec.release(); record = null }
                // capture died (policy/preempt) — retry once the demand persists
                Thread { Thread.sleep(500); ensure() }.also { it.isDaemon = true }.start()
            }.also { it.isDaemon = true; it.start() }
        }
    }

    private fun maybeRelease() {
        synchronized(lock) {
            if (needed()) return
            val rec = record
            record = null
            thread?.let { t -> thread = null; try { t.join(200) } catch (_: InterruptedException) {} }
            try { rec?.stop() } catch (_: IllegalStateException) {}
            rec?.release()
        }
    }

    fun startRate(r: Int) { if (r != rate) { rate = r; if (record != null) { stopNow(); ensure() } } }
    private fun stopNow() {
        synchronized(lock) {
            val rec = record
            record = null
            thread?.let { t -> thread = null; try { t.join(200) } catch (_: InterruptedException) {} }
            try { rec?.stop() } catch (_: IllegalStateException) {}
            rec?.release()
        }
    }
}

// Core-facing mic driver: registers/unregisters a fan-out tap. No AudioRecord
// of its own — MicBus is the sole capture client. `gate` must return true to
// hand samples to the core — background pages do not own the voice.
class MicDriver(private val context: Context, private val gate: () -> Boolean, private val post: (ShortArray) -> Unit) : com.freaklair.hermesgadget.MicDriver {
    @Volatile var busy = false
        private set
    private val tap: (ShortArray) -> Unit = { samples -> if (gate()) post(samples) }

    override fun start(rate: Int): Boolean {
        stop()
        if (!gate()) return false
        MicBus.startRate(rate)
        MicBus.registerCore(tap)
        busy = true
        return true
    }

    override fun stop() {
        if (!busy) return
        MicBus.unregisterCore(tap)
        busy = false
    }
}

// Speaker: AudioTrack streaming with a ring buffer; write() never blocks, the
// core keeps ~0.5 s of lead and we hold ~1.5 s, per port_audio.cpp semantics.
// `gate` decides ownership: background pages' audio is dropped, one voice at a time.
class SpeakerDriver(context: Context, private val gate: () -> Boolean = { true }) : SpeakerDriver {
    @Volatile private var rate = 16000
    @Volatile private var volume = 100
    @Volatile private var track: AudioTrack? = null
    @Volatile private var streamEnded = false
    @Volatile private var pending = 0
    @Volatile var streaming = false   // true from begin() until end()/abort()
        private set
    @Volatile var audioAt = 0L        // last begin() wall-clock ms
    private var endAt = 0L            // last end() wall-clock ms — drain rule
    private val drainMs = 1200L       // "fully drained" = this quiet after audio.end
    private val lock = Object()
    private var minSize = 0

    override fun begin(rate: Int): Boolean {
        if (!gate()) { android.util.Log.i("hgspeaker", "begin rate=$rate REFUSED gate"); return false }
        audioAt = System.currentTimeMillis()
        val old = track
        if (old != null && rate == this.rate) {
            val idle = System.currentTimeMillis() - endAt
            if (!streamEnded || idle < drainMs) {
                // Next queued reply (or a new stream within the drain window):
                // append to the live track. Replacing it would silently drop
                // the previous stream's buffered tail (~1 s), which is exactly
                // the "queue answers, each one cuts off" symptom.
                streamEnded = false
                streaming = true
                android.util.Log.i("hgspeaker", "begin rate=$rate CONTINUE track")
                return true
            }
            // same rate but long-quiet since audio.end: the track has fully
            // drained — rebuild it instead of holding audio power across silence
        }
        if (old != null) {
            // rate switch (or drained idle rebuild): let the old tail play out,
            // then rebuild
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < drainMs) {
                val left = synchronized(lock) { pending } - old.playbackHeadPosition
                if (left <= 0) break
                Thread.sleep(50)
            }
            try { old.stop() } catch (_: IllegalStateException) {}
            old.release()
            track = null
            android.util.Log.i("hgspeaker", "begin rate=$rate REBUILT after drain")
        } else {
            android.util.Log.i("hgspeaker", "begin rate=$rate")
        }
        this.rate = rate
        streamEnded = false
        pending = 0
        minSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) return false
        val cap = maxOf(minSize * 4, rate) // ~1 s min
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(rate).build())
            .setBufferSizeInBytes(cap)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volumePercent())
        t.play()
        track = t
        streaming = true
        return true
    }

    private fun volumePercent(): Float {
        val x = volume / 100f
        return x * x // perceptual
    }

    override fun write(samples: ShortArray) {
        val t = track ?: return
        t.write(samples, 0, samples.size) // streams; blocks at most one buffer
        synchronized(lock) { pending += samples.size }
    }

    override fun end() {
        streamEnded = true
        streaming = false
        endAt = System.currentTimeMillis()
        val t = track
        val pl = if (t != null) t.playbackHeadPosition.toLong() * 1000L / rate else 0L
        android.util.Log.i("hgspeaker", "end pendingMs=${pending.toLong() * 1000L / rate} playedMs=$pl")
    }

    override fun abort() {
        streaming = false
        val t = track
        if (t != null) android.util.Log.i("hgspeaker",
            "abort pendingMs=${pending.toLong() * 1000L / rate} playedMs=${t.playbackHeadPosition.toLong() * 1000L / rate}")
        track = null
        streamEnded = false
        pending = 0
        try { t?.pause() } catch (_: IllegalStateException) {}
        t?.flush()
        t?.release()
    }

    override fun busy(): Boolean {
        if (pending <= 0 && streamEnded) return false
        val t = track ?: return false
        val played = t.playbackHeadPosition.toLong() * 1_000_000L / rate
        val writtenMs = pending.toLong() * 1_000_000L / rate
        return writtenMs > played + 5_000 || (!streamEnded && pending > 0)
    }

    override fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        track?.let { try { it.setVolume(volumePercent()) } catch (_: IllegalStateException) {} }
    }
}
