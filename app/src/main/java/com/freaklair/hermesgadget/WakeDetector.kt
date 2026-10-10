package com.freaklair.hermesgadget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

// "Hey Hermes" wake word, fully on-device (sherpa-onnx zipformer KWS, int8).
//
// A dedicated AudioRecord pump owns the mic while the foreground core is
// idle. The moment the core starts listening (hold/talk session) the pump
// hands the mic back — one capture client at a time, no AudioRecord fights.
//
// Fire path: press the Talk button on the foreground engine only, then
// release it when an energy VAD sees a pause (end of utterance), capped at
// MAX_HOLD_MS. Hands-free: long sentences are never cut off.
class WakeDetector(private val context: Context) {

    companion object {
        private const val TAG = "hgwake"
        private const val SR = 16000
        private const val COOLDOWN_MS = 3000L
        private const val MAX_HOLD_MS = 15000L   // absolute cap on the emulated hold
        private const val SILENCE_MS = 1500L     // pause that counts as end of utterance
        private const val MIN_HOLD_MS = 700L     // never release before this
        private const val VOICE_RMS = 250f       // speech floor: room hum sits <100
        const val BTN_TALK = 0                   // hg::Button::Talk
    }

    @Volatile var target: PageEngine? = null
    @Volatile private var started = false
    @Volatile private var active = false
    @Volatile private var lastFire = 0L
    @Volatile private var chunksIn = 0L
    @Volatile private var lastBlockLog = 0L
    @Volatile private var holding = false
    @Volatile private var holdStartAt = 0L
    @Volatile private var lastVoiceAt = 0L
    @Volatile private var heardVoice = false
    @Volatile private var minHoldAt = 0L
    private val holdCapRelease = Runnable { endHold(target) }
    private var spotter: KeywordSpotter? = null
    private var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (started) return
        started = true
        try {
            val cfg = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SR, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        joiner = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    ),
                    tokens = "kws/tokens.txt",
                    numThreads = 2,
                ),
                maxActivePaths = 4,
                keywordsFile = "kws/keywords.txt",
                keywordsScore = 1.5f,
                keywordsThreshold = 0.15f,
                numTrailingBlanks = 2,
            )
            spotter = KeywordSpotter(context.assets, cfg)
            stream = spotter?.createStream("")
            Log.i(TAG, "KWS loaded")
        } catch (e: Throwable) {
            Log.e(TAG, "KWS init failed", e)
            return
        }
        // samples arrive from MicBus — the single app-wide capture client
        if (active) com.freaklair.hermesgadget.drivers.MicBus.setWake { feed(it) }
    }

    // set before start(): initial toggle state from prefs
    fun setInitialState(on: Boolean) { active = on }

    // 20 ms PCM16 chunks from MicBus. Feed model; if a keyword fires and the
    // foreground page is listening-idle, emulate a talk-button hold.
    // UI toggle. Off = wake tap removed from the bus, mic released to the
    // system entirely (privacy dot goes dark) when no page is listening.
    fun setEnabled(on: Boolean) {
        if (active == on) return
        active = on
        if (!started) return
        if (on) com.freaklair.hermesgadget.drivers.MicBus.setWake { feed(it) }
        else com.freaklair.hermesgadget.drivers.MicBus.setWake(null)
        Log.i(TAG, if (on) "wake ON" else "wake OFF")
    }

    fun enabled(): Boolean = active

    private fun blocked(reason: String) {
        val t = System.currentTimeMillis()
        if (t - lastBlockLog < 5000) return
        lastBlockLog = t
        Log.i(TAG, "armed, blocked: $reason (chunks=$chunksIn)")
    }

    fun feed(samples: ShortArray) {
        if (!active) return
        chunksIn++
        val e0 = target
        if (holding && e0 != null) monitorHold(e0, samples)
        val s = spotter ?: return
        val st = stream ?: return
        val e = e0
        if (e == null) { blocked("no target"); return }
        if (!e.foreground) { blocked("page background"); return }
        if (e.muted) { blocked("page muted"); return }
        if (e.micActive()) { if (!holding) blocked("core owns mic"); return }
        if (System.currentTimeMillis() - lastFire < COOLDOWN_MS) return
        try {
            st.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, SR)
            while (s.isReady(st)) s.decode(st)
            val r = s.getResult(st)
            if (r.keyword.isNotEmpty()) {
                lastFire = System.currentTimeMillis()
                s.reset(st)
                Log.i(TAG, "match '${r.keyword}'")
                fire(e)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode issue", t)
            try { stream = spotter?.createStream("") } catch (_: Exception) {}
        }
    }

    // Wake -> open a hands-free talk hold on the foreground engine. Release
    // is decided by an energy VAD inside the audio stream (pause = finished),
    // not a blind timer — long sentences never get cut off. Hard cap still
    // exists so a stuck mic can't hold the button forever.
    private fun fire(e: PageEngine) {
        Log.i(TAG, "wake -> ${e.name}")
        val now = System.currentTimeMillis()
        holding = true
        holdStartAt = now
        minHoldAt = now + MIN_HOLD_MS
        lastVoiceAt = now
        heardVoice = false
        e.post { if (e.ptr != 0L) NativeBridge.nativeButton(e.ptr, BTN_TALK, 1) }
        main.removeCallbacks(holdCapRelease)
        main.postDelayed(holdCapRelease, MAX_HOLD_MS)
    }

    // Runs from the audio pump while a hold is open: feed() receives samples
    // even when the core owns the mic (MicBus fans out to every tap).
    private fun monitorHold(e: PageEngine, samples: ShortArray) {
        var acc = 0.0
        for (v in samples) acc += v * v
        val rms = Math.sqrt(acc / samples.size).toFloat()
        val now = System.currentTimeMillis()
        if (rms >= VOICE_RMS) {
            lastVoiceAt = now
            heardVoice = true
        }
        if (!holding) return
        // a pause only ends the hold AFTER speech started — a thinking pause
        // right after the wake word must not close the gate early
        if (heardVoice && now >= minHoldAt && now - lastVoiceAt > SILENCE_MS) {
            endHold(e)
            return
        }
        if (now - holdStartAt > MAX_HOLD_MS) endHold(e)
    }

    private fun endHold(e: PageEngine?) {
        if (!holding) return
        holding = false
        main.removeCallbacks(holdCapRelease)
        val t = e ?: target
        if (t != null) {
            Log.i(TAG, "hold closed (${System.currentTimeMillis() - holdStartAt} ms)")
            t.post { if (t.ptr != 0L) NativeBridge.nativeButton(t.ptr, BTN_TALK, 0) }
        }
    }

    fun shutdown() {
        started = false
        endHold(target)
        holding = false
        com.freaklair.hermesgadget.drivers.MicBus.setWake(null)
        try { spotter?.release() } catch (_: Exception) {}
    }
}
