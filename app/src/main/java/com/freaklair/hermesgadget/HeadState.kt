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
            val st = ui.status.lowercase()
            // A live tool/status phrase is the strongest "doing stuff" signal we have —
            // it outranks the screen heuristic, so a slow API call or web search shows
            // working/searching even while the core itself has timed out to idle.
            if (st.isNotEmpty()) return if ("search" in st) SEARCHING else WORKING
            return when {
                ui.screen == "thinking" || ui.screen == "responding" ->
                    // Composing the reply / generating audio without a status line.
                    THINKING
                else -> IDLE
            }
        }
    }
}
