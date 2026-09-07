package io.github.j3r0nim0.anvil

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var wallet: TextInputEditText
    private lateinit var walletError: TextView
    private lateinit var threads: SeekBar
    private lateinit var threadsLabel: TextView
    private lateinit var thermal: SeekBar
    private lateinit var thermalLabel: TextView
    private lateinit var chargingOnly: MaterialSwitch
    private lateinit var toggle: MaterialButton
    private lateinit var hashrate: TextView
    private lateinit var temp: TextView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private var clickLockUntil = 0L

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* mining can still run; notification may be hidden */ }

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wallet = findViewById(R.id.wallet)
        walletError = findViewById(R.id.walletError)
        threads = findViewById(R.id.threads)
        threadsLabel = findViewById(R.id.threadsLabel)
        thermal = findViewById(R.id.thermal)
        thermalLabel = findViewById(R.id.thermalLabel)
        chargingOnly = findViewById(R.id.chargingOnly)
        toggle = findViewById(R.id.toggle)
        hashrate = findViewById(R.id.hashrate)
        temp = findViewById(R.id.temp)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        wallet.setText(prefs.getString("wallet", "") ?: "")
        threads.progress = prefs.getInt("threads", 50).coerceIn(25, 100)
        thermal.progress = prefs.getInt("thermal", 65).coerceIn(50, 85)
        chargingOnly.isChecked = prefs.getBoolean("charging_only", true)
        updateLabels()

        threads.setOnSeekBarChangeListener(simple { updateLabels() })
        thermal.setOnSeekBarChangeListener(simple { updateLabels() })
        findViewById<ImageButton>(R.id.openLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        linkifyFooter()
        toggle.setOnClickListener { onToggle() }
        toggle.setOnLongClickListener {
            if (!armed()) return@setOnLongClickListener false
            if (!MiningService.isRunning || MiningService.isBusy) return@setOnLongClickListener false
            startService(Intent(this, MiningService::class.java).setAction(MiningService.ACTION_STOP))
            render()
            true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        save()
    }

    private fun armed(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now < clickLockUntil || MiningService.isBusy) return false
        clickLockUntil = now + 800
        return true
    }

    private fun onToggle() {
        if (!armed()) return
        if (MiningService.isRunning) {
            val action = if (MiningState.userPaused) {
                MiningService.ACTION_RESUME
            } else {
                MiningService.ACTION_PAUSE
            }
            startService(Intent(this, MiningService::class.java).setAction(action))
            render()
            return
        }

        val addr = Wallet.parse(wallet.text?.toString().orEmpty())
        wallet.setText(addr)
        if (!Wallet.isValid(addr)) {
            walletError.visibility = android.view.View.VISIBLE
            walletError.text = "Need a mainnet address (95 or 106 chars, starts with 4 or 8). Got ${addr.length}."
            return
        }
        walletError.visibility = android.view.View.GONE
        wallet.setText(addr)
        save()

        val intent = Intent(this, MiningService::class.java).apply {
            action = MiningService.ACTION_START
            putExtra(MiningService.EXTRA_WALLET, addr)
            putExtra(MiningService.EXTRA_THREADS_PERCENT, threads.progress)
            putExtra(MiningService.EXTRA_THERMAL_THRESHOLD, thermal.progress)
            putExtra(MiningService.EXTRA_CHARGING_ONLY, chargingOnly.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
        render()
    }

    /** Make the moneroocean.stream link in the footer open the payout page in a browser. */
    private fun linkifyFooter() {
        val footer = findViewById<TextView>(R.id.footer)
        val text = footer.text.toString()
        val link = "moneroocean.stream"
        val start = text.indexOf(link)
        if (start < 0) return
        val spannable = SpannableString(text)
        spannable.setSpan(
            URLSpan("https://moneroocean.stream"),
            start, start + link.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        footer.text = spannable
        footer.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun render() {
        val running = MiningService.isRunning
        val busy = MiningService.isBusy
        toggle.isEnabled = !busy
        toggle.text = when {
            busy && !running -> "Starting…"
            busy && running -> "Stopping…"
            !running -> "Start"
            MiningState.userPaused -> "Resume"
            else -> "Pause"
        }
        wallet.isEnabled = !running && !busy
        threads.isEnabled = !running && !busy
        thermal.isEnabled = !running && !busy
        chargingOnly.isEnabled = !running && !busy

        if (!running) {
            hashrate.text = "— H/s"
            temp.text = "— °C"
            status.text = "Idle"
            log.text = ""
            return
        }

        val hs = MiningState.hashrate10s
        hashrate.text = formatHs(hs)
        temp.text = if (MiningState.tempCelsius > 0) {
            "${MiningState.tempCelsius}°C"
        } else {
            "— °C"
        }
        val pause = MiningState.pauseReason
        status.text = when {
            pause.isNotEmpty() -> pause
            MiningState.pool.isNotEmpty() ->
                "${MiningState.pool}  ·  ${MiningState.accepted} accepted  ·  ${MiningState.rejected} rejected  ·  ${formatUptime(MiningState.uptimeSecs)}"
            MiningState.lastLogLine.contains("DNS error", ignoreCase = true) ->
                "Can't reach pool (DNS). Check network / VPN settings."
            else -> "Starting… first run may take a few minutes"
        }
        log.text = MiningState.lastLogLine
    }

    private fun updateLabels() {
        threadsLabel.text = "Threads  ${threads.progress}%"
        thermalLabel.text = "CPU pause  ${thermal.progress}°C"
    }

    private fun save() {
        prefs.edit()
            .putString("wallet", wallet.text?.toString().orEmpty())
            .putInt("threads", threads.progress)
            .putInt("thermal", thermal.progress)
            .putBoolean("charging_only", chargingOnly.isChecked)
            .apply()
    }

    private fun formatHs(v: Double): String {
        return when {
            v >= 1_000_000 -> String.format("%.2f MH/s", v / 1_000_000)
            v >= 1_000 -> String.format("%.1f kH/s", v / 1_000)
            v > 0 -> String.format("%.0f H/s", v)
            else -> "— H/s"
        }
    }

    private fun formatUptime(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun simple(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
