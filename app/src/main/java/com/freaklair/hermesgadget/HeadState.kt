package com.freaklair.hermesgadget

// Maps one page's live signals onto a robot-head state (MyBots). The same
// contract a later native-Kotlin renderer must satisfy — keep it signal-based,
// no invented timers. The core knows whether it waits for a reply or runs a
// turn, so thinking/working come from its screen + status, not from clocks.
enum class HeadState {
    IDLE, THINKING, WORKING, SEARCHING, LISTENING, SPEAKING, HAPPY, ERROR, SLEEPING;

    val js: String get() = name.lowercase()

    companion object {
        // Priority: sleep > connection > mic > speaker > core. mic and speaker
        // are the local truth (what the user can hear/see right now); the core
        // screen decides what the head does while nothing plays.
        fun derive(engine: PageEngine?, screenOn: Boolean): HeadState {
            if (!screenOn) return SLEEPING
            val e = engine ?: return IDLE
            if (!e.wsOpen) return if (e.closeFails >= 2) ERROR else SEARCHING
            if (!e.isRunning()) return SEARCHING
            if (e.micActive()) return LISTENING
            if (e.speakingNow()) return SPEAKING
            val ui = e.lastUi
            return if (ui.screen == "thinking" || ui.screen == "responding") {
                // "Thinking": agent is turning the request over. A live status
                // ("Sending...", tool phrases) means it is actively doing stuff.
                // "Responding" without audible frames = composing the reply,
                // audio being generated — same face, no silent idle.
                if (ui.status.isNotEmpty()) WORKING else THINKING
            } else IDLE
        }
    }
}
