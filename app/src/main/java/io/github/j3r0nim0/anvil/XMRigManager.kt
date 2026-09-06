package io.github.j3r0nim0.anvil

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled MoneroOcean XMRig (`libxmrig.so`) as a process.
 * Connects straight to the pool over TLS — no on-device stratum proxy.
 */
class XMRigManager(
    private val context: Context,
    private val poolUrl: String,
    private val poolTls: Boolean,
    private val workerName: String,
    private val difficulty: Int,
) {

    companion object {
        private const val TAG = "XMRigManager"
        private const val BINARY_SO = "libxmrig.so"
        private const val CONFIG_NAME = "xmrig_config.json"
        private const val CONFIG_META = "xmrig_config_meta.txt"
        const val DEBUG_LOG_NAME = "debug_xmrig.log"
        private const val DEBUG_LOG_MAX_BYTES = 400_000L

        fun clearAlgoCache(context: Context) {
            File(context.filesDir, CONFIG_NAME).delete()
            File(context.filesDir, CONFIG_META).delete()
        }
    }

    private var process: Process? = null

    @Volatile private var statsPool = ""
    @Volatile private var statsAccepted = 0
    @Volatile private var statsRejected = 0
    @Volatile private var statsHashrate10s = 0.0
    @Volatile private var statsHashrate60s = 0.0
    @Volatile private var connectionStartMs = 0L

    private val reSpeed = Regex(
        """10s/60s/15m[:\s]+([\d.]+|n/a)\s+([\d.]+|n/a)\s+([\d.]+|n/a)\s+([KMG]?)H/s""",
        RegexOption.IGNORE_CASE,
    )
    private val rePool = Regex("""(?:use pool|new job from)\s+(\S+)""")
    private val reAccepted = Regex("""accepted\s+\((\d+)/(\d+)\)""")
    private val reDisconnect = Regex("""net\s+.*(disconnect|offline|read error)""", RegexOption.IGNORE_CASE)

    private val debugLogFile by lazy { File(context.filesDir, DEBUG_LOG_NAME) }
    private val logFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun appendDebugLog(line: String) {
        try {
            debugLogFile.appendText("${logFmt.format(Date())}  $line\n")
            if (debugLogFile.length() > DEBUG_LOG_MAX_BYTES) {
                val lines = debugLogFile.readLines()
                debugLogFile.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun start(wallet: String, threadsPercent: Int) {
        if (process?.isAlive == true) {
            Log.w(TAG, "start() while alive — stopping first")
            stopLocked()
        }

        statsPool = ""
        statsAccepted = 0
        statsRejected = 0
        statsHashrate10s = 0.0
        statsHashrate60s = 0.0
        connectionStartMs = 0L

        val binary = getBinary()
        val config = writeConfig(wallet, threadsPercent)

        appendDebugLog("=== XMRig starting (wallet=${wallet.take(8)}… threads=$threadsPercent%) ===")
        Log.i(TAG, "Launching: ${binary.absolutePath} --config=${config.absolutePath} --no-color")

        process = ProcessBuilder(binary.absolutePath, "--config=${config.absolutePath}", "--no-color")
            .redirectErrorStream(true)
            .directory(context.filesDir)
            .start()
            .also { proc ->
                Thread({
                    try {
                        proc.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                Log.i(TAG, "xmrig: $line")
                                appendDebugLog(line)
                                MiningState.lastLogLine = line
                                parseStatsLine(line)
                            }
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "xmrig drain ended: ${e.message}")
                        appendDebugLog("=== drain ended: ${e.message} ===")
                    }
                    val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                    Log.w(TAG, "XMRig exited with code $code")
                    appendDebugLog("=== XMRig exited with code $code ===")
                }, "xmrig-drain").apply { isDaemon = true; start() }
            }

        Log.i(TAG, "XMRig started (wallet=${wallet.take(8)}…, threads=$threadsPercent%)")
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val proc = process ?: return
        process = null
        appendDebugLog("=== XMRig stopped ===")
        Log.i(TAG, "XMRig stopping")
        try {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
            runCatching { proc.destroyForcibly() }
        }
        Log.i(TAG, "XMRig stopped")
    }

    @Synchronized
    fun pause() {
        sendStdin("p")
        Log.i(TAG, "XMRig paused (stdin p)")
    }

    @Synchronized
    fun resumeHashing() {
        sendStdin("r")
        Log.i(TAG, "XMRig resumed (stdin r)")
    }

    private fun sendStdin(cmd: String) {
        val proc = process ?: return
        if (!proc.isAlive) {
            Log.w(TAG, "stdin write skipped — process not alive")
            return
        }
        try {
            proc.outputStream.let { out ->
                out.write("$cmd\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stdin write failed: ${e.message}")
        }
    }

    fun reconfigure(wallet: String, threadsPercent: Int) {
        stop()
        start(wallet, threadsPercent)
    }

    val isRunning: Boolean get() = process?.isAlive == true

    private fun getBinary(): File {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_SO)
        check(binary.exists()) { "XMRig binary not found at ${binary.absolutePath}." }
        Log.i(TAG, "Binary: ${binary.absolutePath} (${binary.length()} bytes)")
        return binary
    }

    private fun writeConfig(wallet: String, threadsPercent: Int): File {
        val configFile = File(context.filesDir, CONFIG_NAME)
        val metaFile = File(context.filesDir, CONFIG_META)
        val poolUser = if (difficulty > 0) "$wallet+$difficulty" else wallet
        val metaKey = "v1:$threadsPercent:$poolUrl:$poolTls"

        if (configFile.exists() && metaFile.exists() && metaFile.readText().trim() == metaKey) {
            val existing = configFile.readText()
            val patched = existing.replace(
                Regex(""""user"\s*:\s*"[^"]*""""),
                """"user": "$poolUser"""",
            )
            if (patched != existing) {
                configFile.writeText(patched)
                Log.i(TAG, "Config: user patched (algo-perf preserved)")
            }
            return configFile
        }

        val template = context.assets.open("xmrig_config_template.json").bufferedReader().readText()
        val totalCores = Runtime.getRuntime().availableProcessors()
        val threadCount = maxOf(1, (totalCores * threadsPercent / 100.0).toInt())
        val threadEntry = """{"low_power_mode":false,"affine_to_cpu":false}"""
        val threadArray = "[" + (0 until threadCount).joinToString(",") { threadEntry } + "]"
        Log.i(TAG, "Thread config: $threadCount / $totalCores cores ($threadsPercent%)")

        val config = template
            .replace("\"__WALLET_ADDRESS__\"", "\"$poolUser\"")
            .replace("\"__POOL_URL__\"", "\"$poolUrl\"")
            .replace("\"__POOL_TLS__\"", "$poolTls")
            .replace("\"__RIG_ID__\"", "\"$workerName\"")
            .replace("\"__THREADS_PERCENT__\"", "$threadsPercent")
            .replace("\"__THREAD_ARRAY__\"", threadArray)
        configFile.writeText(config)
        metaFile.writeText(metaKey)
        Log.i(TAG, "Config: written (pool=$poolUrl tls=$poolTls rig=$workerName threads=$threadsPercent%)")
        return configFile
    }

    private fun parseStatsLine(line: String) {
        var changed = false

        reSpeed.find(line)?.let { m ->
            val unitMult = when (m.groupValues[4].uppercase()) {
                "K" -> 1_000.0
                "M" -> 1_000_000.0
                "G" -> 1_000_000_000.0
                else -> 1.0
            }
            statsHashrate10s = (m.groupValues[1].toDoubleOrNull() ?: 0.0) * unitMult
            statsHashrate60s = (m.groupValues[2].toDoubleOrNull() ?: 0.0) * unitMult
            changed = true
        }

        rePool.find(line)?.let { m ->
            val newPool = m.groupValues[1]
            if (statsPool.isEmpty() && newPool.isNotEmpty()) {
                connectionStartMs = System.currentTimeMillis()
            }
            statsPool = newPool
            changed = true
        }

        if (reDisconnect.containsMatchIn(line)) {
            statsPool = ""
            connectionStartMs = 0L
            changed = true
        }

        reAccepted.find(line)?.let { m ->
            statsAccepted = m.groupValues[1].toIntOrNull() ?: statsAccepted
            statsRejected = m.groupValues[2].toIntOrNull() ?: statsRejected
            changed = true
        }

        if (changed) emitStats()
    }

    fun emitStats() {
        val uptimeSecs = if (connectionStartMs > 0) {
            ((System.currentTimeMillis() - connectionStartMs) / 1000).toInt()
        } else {
            0
        }
        MiningState.hashrate10s = statsHashrate10s
        MiningState.hashrate60s = statsHashrate60s
        MiningState.accepted = statsAccepted
        MiningState.rejected = statsRejected
        MiningState.pool = statsPool
        MiningState.uptimeSecs = uptimeSecs
    }
}
