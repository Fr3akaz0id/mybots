package com.freaklair.hermesgadget

// Maps one page's live signals onto a robot-head state (MyBots). The same
// contract a later native-Kotlin renderer must satisfy — keep it signal-based,
// no invented timers.
enum class HeadState {
    IDLE, THINKING, SEARCHING, LISTENING, SPEAKING, ERROR, SLEEPING;

    val js: String get() = name.lowercase()

    companion object {
        // screenOn covers "app visible"; pager passes null engine on the
        // settings page. Priority: sleep > connection > mic > audio > thinking.
        fun derive(engine: PageEngine?, screenOn: Boolean): HeadState {
            if (!screenOn) return SLEEPING
            val e = engine ?: return IDLE
            if (!e.wsOpen) return if (e.closeFails >= 2) ERROR else SEARCHING
            if (!e.isRunning()) return SEARCHING
            if (e.micActive()) return LISTENING
            if (e.speakingNow()) return SPEAKING
            val now = System.currentTimeMillis()
            // user spoke, hub-side agent is chewing on it; a reply that already
            // started (audioAt after touch-up) rules this out
            val sinceUp = now - e.lastTouchUpAt
            if (e.lastTouchUpAt > 0 && sinceUp < 15_000 && e.lastAudioAt() < e.lastTouchUpAt)
                return THINKING
            return IDLE
        }
    }
}
