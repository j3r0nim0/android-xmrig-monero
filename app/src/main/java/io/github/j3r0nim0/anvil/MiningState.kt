package io.github.j3r0nim0.anvil

/**
 * Process-level stats the service writes and [MainActivity] reads.
 * No Flutter / bound service — the UI polls every second while visible.
 */
object MiningState {
    @Volatile var hashrate10s: Double = 0.0
    @Volatile var hashrate60s: Double = 0.0
    @Volatile var accepted: Int = 0
    @Volatile var rejected: Int = 0
    @Volatile var pool: String = ""
    @Volatile var uptimeSecs: Int = 0
    @Volatile var tempCelsius: Int = 0
    @Volatile var tempSource: String = ""
    @Volatile var pauseReason: String = ""
    @Volatile var userPaused: Boolean = false
    @Volatile var lastLogLine: String = ""

    fun reset() {
        hashrate10s = 0.0
        hashrate60s = 0.0
        accepted = 0
        rejected = 0
        pool = ""
        uptimeSecs = 0
        tempCelsius = 0
        tempSource = ""
        pauseReason = ""
        userPaused = false
        lastLogLine = ""
    }
}
