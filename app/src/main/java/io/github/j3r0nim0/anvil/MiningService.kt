package io.github.j3r0nim0.anvil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * ForegroundService that owns the XMRig process.
 * Wake lock, thermal pause, charging-only, persistent notification.
 */
class MiningService : Service() {

    companion object {
        private const val TAG = "MiningService"

        const val ACTION_START = "io.github.j3r0nim0.anvil.START"
        const val ACTION_STOP = "io.github.j3r0nim0.anvil.STOP"
        const val ACTION_PAUSE = "io.github.j3r0nim0.anvil.PAUSE"
        const val ACTION_RESUME = "io.github.j3r0nim0.anvil.RESUME"

        const val EXTRA_WALLET = "wallet"
        const val EXTRA_THREADS_PERCENT = "threads_percent"
        const val EXTRA_THERMAL_THRESHOLD = "thermal_threshold"
        const val EXTRA_CHARGING_ONLY = "charging_only"
        const val EXTRA_POOL_HOST = "pool_host"
        const val EXTRA_POOL_PORT = "pool_port"
        const val EXTRA_POOL_TLS = "pool_tls"

        const val DEFAULT_POOL_HOST = "gulf.moneroocean.stream"
        const val DEFAULT_POOL_PORT = 20032
        const val DEFAULT_POOL_TLS = true

        /** MoneroOcean VarDiff start (`wallet+N`). 0 = pool auto. */
        const val START_DIFFICULTY = 30000

        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "xmrig_mining"

        private const val BATTERY_PAUSE_TENTHS = 400   // 40°C
        private const val BATTERY_RESUME_TENTHS = 370  // 37°C

        @Volatile
        var isRunning = false
            private set

        /** True while start/stop is in flight — UI should ignore extra taps. */
        @Volatile
        var isBusy = false
            private set

        private const val WORKER_PREFS = "xmrig_worker"
        private const val WORKER_KEY = "worker_name"

        fun poolWorkerName(context: Context): String {
            val prefs = context.getSharedPreferences(WORKER_PREFS, Context.MODE_PRIVATE)
            prefs.getString(WORKER_KEY, null)?.let { return it }
            val name = "AX-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit().putString(WORKER_KEY, name).apply()
            return name
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var xmrig: XMRigManager? = null
    private var thermal: ThermalMonitor? = null

    private var currentWallet = ""
    private var currentPoolUrl = "$DEFAULT_POOL_HOST:$DEFAULT_POOL_PORT"
    private var currentPoolTls = DEFAULT_POOL_TLS
    private var currentThreadsPercent = 50

    private var thermalPaused = false
    private var batteryThermalPaused = false
    private var chargingPaused = false
    private var userPaused = false
    private var chargingOnly = true
    private var lastBatteryTempC = 0

    private var ioThread: HandlerThread? = null
    private var io: Handler? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must be in foreground before any stop/start mash, or Android kills us
        // with "startForegroundService did not then call startForeground".
        promoteForeground("Starting…")
        val thread = HandlerThread("mining-io").also { it.start() }
        ioThread = thread
        io = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_START -> {
                val wallet = intent.getStringExtra(EXTRA_WALLET) ?: run {
                    Log.e(TAG, "START missing wallet")
                    return START_NOT_STICKY
                }
                chargingOnly = intent.getBooleanExtra(EXTRA_CHARGING_ONLY, true)
                val threads = intent.getIntExtra(EXTRA_THREADS_PERCENT, 50)
                val threshold = intent.getIntExtra(EXTRA_THERMAL_THRESHOLD, 65)
                val poolHost = intent.getStringExtra(EXTRA_POOL_HOST)
                    ?.takeIf { it.isNotBlank() } ?: DEFAULT_POOL_HOST
                val poolPort = intent.getIntExtra(EXTRA_POOL_PORT, DEFAULT_POOL_PORT)
                    .let { if (it in 1..65535) it else DEFAULT_POOL_PORT }
                val poolTls = intent.getBooleanExtra(EXTRA_POOL_TLS, DEFAULT_POOL_TLS)
                startMining(wallet, threads, threshold, poolHost, poolPort, poolTls)
            }
            ACTION_PAUSE -> pauseByUser()
            ACTION_RESUME -> resumeByUser()
            ACTION_STOP -> {
                isBusy = true
                io?.post {
                    tearDown()
                    stopSelf()
                } ?: run {
                    tearDown()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        io?.removeCallbacksAndMessages(null)
        tearDown()
        ioThread?.quitSafely()
        ioThread = null
        io = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMining(
        wallet: String,
        threadsPercent: Int,
        thermalThreshold: Int,
        poolHost: String,
        poolPort: Int,
        poolTls: Boolean,
    ) {
        if (isRunning || isBusy) {
            Log.i(TAG, "startMining ignored (running=$isRunning busy=$isBusy)")
            return
        }
        isBusy = true
        isRunning = true

        currentWallet = wallet
        currentThreadsPercent = threadsPercent
        currentPoolUrl = "$poolHost:$poolPort"
        currentPoolTls = poolTls
        MiningState.reset()
        registerChargingReceiver()
        promoteForeground("Starting…")

        thermal = ThermalMonitor(thermalThreshold).apply {
            onTemperature = { tempC ->
                when {
                    tempC > 0 -> {
                        MiningState.tempCelsius = tempC
                        MiningState.tempSource = "cpu"
                    }
                    lastBatteryTempC > 0 -> {
                        MiningState.tempCelsius = lastBatteryTempC
                        MiningState.tempSource = "battery"
                    }
                    else -> {
                        MiningState.tempCelsius = 0
                        MiningState.tempSource = ""
                    }
                }
                xmrig?.emitStats()
            }
            onOverheat = {
                thermalPaused = true
                xmrig?.pause()
                updateNotification("Paused — CPU too hot")
                publishPauseState()
            }
            onCooledDown = {
                thermalPaused = false
                tryResumeHashing()
                publishPauseState()
            }
            start()
        }

        val startWallet = wallet
        val startThreads = threadsPercent
        io?.post {
            // User may have stopped while this task was queued
            if (!isRunning) {
                isBusy = false
                Log.i(TAG, "startMining deferred — stop was requested; aborting")
                return@post
            }
            try {
                acquireWakeLock()   // only while hashing — not while queued
                xmrig?.stop()
                xmrig = newXmrig().apply { start(startWallet, startThreads) }
                // Check if user paused during the startup window
                if (userPaused || thermalPaused || batteryThermalPaused || chargingPaused) {
                    xmrig?.pause()
                    updateNotification(currentPauseLabel())
                    publishPauseState()
                    Log.i(TAG, "Mining started but immediately paused per user/thermal state")
                } else {
                    updateNotification("Mining")
                    Log.i(TAG, "Mining started")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "XMRig start failed: ${e.message}", e)
                tearDown()
                stopSelf()
            } finally {
                isBusy = false
            }
        } ?: run {
            isBusy = false
        }
    }

    private fun promoteForeground(statusText: String) {
        try {
            val notif = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    private fun newXmrig() = XMRigManager(
        this, currentPoolUrl, currentPoolTls, poolWorkerName(this), START_DIFFICULTY,
    )

    @Synchronized
    private fun tearDown() {
        xmrig?.stop()
        xmrig = null
        thermal?.stop()
        thermal = null
        releaseWakeLock()
        safeUnregisterReceiver()
        isRunning = false
        isBusy = false
        thermalPaused = false
        batteryThermalPaused = false
        chargingPaused = false
        userPaused = false
        lastBatteryTempC = 0
        MiningState.reset()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        Log.i(TAG, "Mining stopped")
    }

    private var chargingReceiverRegistered = false

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            if (batteryTemp > 0) lastBatteryTempC = batteryTemp / 10

            if (batteryTemp >= BATTERY_PAUSE_TENTHS && !batteryThermalPaused) {
                batteryThermalPaused = true
                xmrig?.pause()
                Log.w(TAG, "Battery too hot: ${batteryTemp / 10}°C — pausing")
                updateNotification("Paused — battery too hot")
                publishPauseState()
            } else if (batteryTemp <= BATTERY_RESUME_TENTHS && batteryThermalPaused) {
                batteryThermalPaused = false
                tryResumeHashing()
                Log.i(TAG, "Battery cooled: ${batteryTemp / 10}°C")
                publishPauseState()
            }

            if (!chargingOnly) return
            if (!charging && !chargingPaused) {
                chargingPaused = true
                xmrig?.pause()
                updateNotification("Paused — not charging")
                publishPauseState()
                Log.i(TAG, "Charging-only: unplugged — paused")
            } else if (charging && chargingPaused) {
                chargingPaused = false
                tryResumeHashing()
                publishPauseState()
                Log.i(TAG, "Charging-only: plugged in")
            }
        }
    }

    private fun registerChargingReceiver() {
        if (!chargingReceiverRegistered) {
            registerReceiver(chargingReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            chargingReceiverRegistered = true
        }
    }

    private fun safeUnregisterReceiver() {
        if (chargingReceiverRegistered) {
            try {
                unregisterReceiver(chargingReceiver)
            } catch (_: Exception) {
            }
            chargingReceiverRegistered = false
        }
    }

    private fun pauseByUser() {
        if (!isRunning || isBusy || userPaused) return
        userPaused = true
        io?.post { xmrig?.pause() } ?: xmrig?.pause()
        updateNotification("Paused")
        publishPauseState()
        Log.i(TAG, "User pause")
    }

    private fun resumeByUser() {
        if (!isRunning || isBusy || !userPaused) return
        userPaused = false
        io?.post { tryResumeHashing() } ?: tryResumeHashing()
        publishPauseState()
        Log.i(TAG, "User resume")
    }

    /** Resume hashing only when no pause flag is still set. */
    private fun tryResumeHashing() {
        if (userPaused || thermalPaused || batteryThermalPaused || chargingPaused) {
            updateNotification(currentPauseLabel())
            return
        }
        xmrig?.resumeHashing()
        updateNotification("Mining")
    }

    private fun currentPauseLabel(): String = when {
        thermalPaused -> "Paused — CPU too hot"
        batteryThermalPaused -> "Paused — battery too hot"
        chargingPaused -> "Paused — not charging"
        userPaused -> "Paused"
        else -> "Mining"
    }

    private fun publishPauseState() {
        MiningState.userPaused = userPaused
        MiningState.pauseReason = when {
            thermalPaused -> "Device too hot"
            batteryThermalPaused -> "Battery too hot"
            chargingPaused -> "Not charging"
            userPaused -> "Paused"
            else -> ""
        }
        xmrig?.emitStats()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "android-xmrig-monero:Mining")
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_stat_mining)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(statusText))
    }
}
