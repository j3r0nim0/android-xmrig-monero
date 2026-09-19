package io.github.j3r0nim0.anvil

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs.get(this) }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var wallet: TextInputEditText
    private lateinit var walletError: TextView
    private lateinit var workerName: TextView
    private lateinit var walletSaved: View
    private lateinit var walletEdit: View
    private lateinit var walletDisplay: TextView
    private lateinit var walletEditTitle: TextView
    private lateinit var saveWallet: MaterialButton
    private lateinit var cancelWallet: MaterialButton
    private lateinit var changeWallet: MaterialButton
    private var editingWallet = false
    private lateinit var threads: SeekBar
    private lateinit var threadsLabel: TextView
    private lateinit var threadTicks: Array<TextView>
    private lateinit var thermal: SeekBar
    private lateinit var thermalLabel: TextView
    private lateinit var thermalBlock: LinearLayout
    private lateinit var batteryMeterBlock: LinearLayout
    private lateinit var batteryMeterLabel: TextView
    private lateinit var batteryMeter: ProgressBar
    private lateinit var chargingOnly: MaterialSwitch
    private lateinit var wifiOnly: MaterialSwitch
    private lateinit var quietHours: MaterialSwitch
    private lateinit var quietPickers: LinearLayout
    private lateinit var quietStartBtn: MaterialButton
    private lateinit var quietEndBtn: MaterialButton
    private lateinit var toggle: MaterialButton
    private lateinit var hashrate: TextView
    private lateinit var temp: TextView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: android.widget.ScrollView
    private lateinit var pauseBanner: TextView
    private lateinit var updateBanner: TextView
    private lateinit var tabMine: View
    private lateinit var tabWallet: View
    private lateinit var tabSettings: View
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var statsRow: View
    private lateinit var accepted: TextView
    private lateinit var rejected: TextView
    private lateinit var noWalletHint: TextView
    private var clickLockUntil = 0L
    private var cpuThermalOk = true
    private var lastThreadIdx = 1
    private var applyingThreads = false

    private val threadSteps = intArrayOf(25, 50, 75, 100)

    private fun threadPercent(): Int = threadSteps[threads.progress.coerceIn(0, threadSteps.lastIndex)]

    private fun indexOfThreadPercent(percent: Int): Int {
        val nearest = threadSteps.minBy { kotlin.math.abs(it - percent) }
        return threadSteps.indexOf(nearest).coerceAtLeast(0)
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) openScanner()
        else Toast.makeText(this, "Camera permission needed to scan", Toast.LENGTH_SHORT).show()
    }

    private val scanWallet = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val addr = result.data?.getStringExtra(ScanActivity.EXTRA_WALLET) ?: return@registerForActivityResult
        wallet.setText(addr)
        walletError.visibility = View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabMine = findViewById(R.id.tabMine)
        tabWallet = findViewById(R.id.tabWallet)
        tabSettings = findViewById(R.id.tabSettings)
        bottomNav = findViewById(R.id.bottomNav)
        wallet = findViewById(R.id.wallet)
        walletError = findViewById(R.id.walletError)
        workerName = findViewById(R.id.workerName)
        walletSaved = findViewById(R.id.walletSaved)
        walletEdit = findViewById(R.id.walletEdit)
        walletDisplay = findViewById(R.id.walletDisplay)
        walletEditTitle = findViewById(R.id.walletEditTitle)
        saveWallet = findViewById(R.id.saveWallet)
        cancelWallet = findViewById(R.id.cancelWallet)
        changeWallet = findViewById(R.id.changeWallet)
        threads = findViewById(R.id.threads)
        threadsLabel = findViewById(R.id.threadsLabel)
        statsRow = findViewById(R.id.statsRow)
        accepted = findViewById(R.id.accepted)
        rejected = findViewById(R.id.rejected)
        noWalletHint = findViewById(R.id.noWalletHint)
        threadTicks = arrayOf(
            findViewById(R.id.tick25),
            findViewById(R.id.tick50),
            findViewById(R.id.tick75),
            findViewById(R.id.tick100),
        )
        threads.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignThreadTicks() }
        thermal = findViewById(R.id.thermal)
        thermalLabel = findViewById(R.id.thermalLabel)
        thermalBlock = findViewById(R.id.thermalBlock)
        batteryMeterBlock = findViewById(R.id.batteryMeterBlock)
        batteryMeterLabel = findViewById(R.id.batteryMeterLabel)
        batteryMeter = findViewById(R.id.batteryMeter)
        chargingOnly = findViewById(R.id.chargingOnly)
        wifiOnly = findViewById(R.id.wifiOnly)
        quietHours = findViewById(R.id.quietHours)
        quietPickers = findViewById(R.id.quietPickers)
        quietStartBtn = findViewById(R.id.quietStart)
        quietEndBtn = findViewById(R.id.quietEnd)
        toggle = findViewById(R.id.toggle)
        hashrate = findViewById(R.id.hashrate)
        temp = findViewById(R.id.temp)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        pauseBanner = findViewById(R.id.pauseBanner)
        updateBanner = findViewById(R.id.updateBanner)

        showTab(R.id.nav_mine)
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        wallet.setText(savedWallet())
        val savedThreads = prefs.getInt(Prefs.THREADS, 50)
        lastThreadIdx = indexOfThreadPercent(savedThreads)
        threads.max = threadSteps.lastIndex
        threads.progress = lastThreadIdx
        thermal.progress = prefs.getInt(Prefs.THERMAL, 65).coerceIn(50, 85)
        chargingOnly.isChecked = prefs.getBoolean(Prefs.CHARGING_ONLY, true)
        wifiOnly.isChecked = prefs.getBoolean(Prefs.WIFI_ONLY, false)
        quietHours.isChecked = prefs.getBoolean(Prefs.QUIET_ON, false)
        updateLabels()
        updateQuietButtons()
        quietPickers.visibility = if (quietHours.isChecked) View.VISIBLE else View.GONE

        cpuThermalOk = ThermalMonitor.isCpuThermalReadable()
        MiningState.cpuThermalReadable = cpuThermalOk
        applyThermalUi()
        showWalletUi()

        workerName.text = "This device is worker ${MiningService.poolWorkerName(this)}"

        threads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (applyingThreads) return
                val idx = threads.progress.coerceIn(0, threadSteps.lastIndex)
                val value = threadSteps[idx]
                if (idx == lastThreadIdx) return
                if (value == 100 && prefs.getInt(Prefs.CPU100, 0) < 2) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("100% CPU threads")
                        .setMessage("All cores, more heat, more battery. Continue at 100%?")
                        .setNegativeButton("Cancel") { _, _ -> snapThreadsBack() }
                        .setPositiveButton("Continue") { _, _ ->
                            prefs.edit().putInt(Prefs.CPU100, prefs.getInt(Prefs.CPU100, 0) + 1).apply()
                            commitThreads(idx)
                        }
                        .setOnCancelListener { snapThreadsBack() }
                        .show()
                    return
                }
                commitThreads(idx)
            }
        })
        thermal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                save()
                if (MiningService.isRunning) sendReconfigure()
            }
        })

        chargingOnly.setOnCheckedChangeListener { _, _ ->
            save()
            if (MiningService.isRunning) sendReconfigure()
        }
        wifiOnly.setOnCheckedChangeListener { _, _ ->
            save()
            if (MiningService.isRunning) sendReconfigure()
        }
        quietHours.setOnCheckedChangeListener { _, checked ->
            quietPickers.visibility = if (checked) View.VISIBLE else View.GONE
            save()
            if (MiningService.isRunning) sendReconfigure()
        }
        quietStartBtn.setOnClickListener { pickTime(true) }
        quietEndBtn.setOnClickListener { pickTime(false) }

        findViewById<View>(R.id.openLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<ImageButton>(R.id.pasteWallet).setOnClickListener { pasteWallet() }
        findViewById<ImageButton>(R.id.copyWallet).setOnClickListener {
            copyText(savedWallet(), "Address copied")
        }
        findViewById<ImageButton>(R.id.scanWallet).setOnClickListener { requestScan() }
        findViewById<MaterialButton>(R.id.verifyMo).setOnClickListener { openMoneroOcean() }
        saveWallet.setOnClickListener { commitWallet() }
        changeWallet.setOnClickListener { enterWalletEdit(changing = true) }
        cancelWallet.setOnClickListener { editingWallet = false; showWalletUi() }
        findViewById<MaterialButton>(R.id.rebench).setOnClickListener { confirmRebench() }
        findViewById<MaterialButton>(R.id.openAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
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

        maybeHandleMoneroIntent(intent)
        checkUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeHandleMoneroIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        cpuThermalOk = ThermalMonitor.isCpuThermalReadable()
        MiningState.cpuThermalReadable = cpuThermalOk
        applyThermalUi()
        render()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        save()
    }

    private fun showTab(id: Int) {
        tabMine.visibility = if (id == R.id.nav_mine) View.VISIBLE else View.GONE
        tabWallet.visibility = if (id == R.id.nav_wallet) View.VISIBLE else View.GONE
        tabSettings.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        if (id == R.id.nav_mine) threads.post { alignThreadTicks() }
    }

    private fun applyThermalUi() {
        if (cpuThermalOk) {
            thermalBlock.visibility = View.VISIBLE
            batteryMeterBlock.visibility = View.GONE
        } else {
            thermalBlock.visibility = View.GONE
            batteryMeterBlock.visibility = View.VISIBLE
        }
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

        val addr = savedWallet()
        if (!Wallet.isValid(addr)) {
            noWalletHint.visibility = View.VISIBLE
            bottomNav.selectedItemId = R.id.nav_wallet
            return
        }
        ensureHarmConsent { startMining(addr) }
    }

    private fun startMining(addr: String) {
        val intent = miningIntent(MiningService.ACTION_START).apply {
            putExtra(MiningService.EXTRA_WALLET, addr)
        }
        ContextCompat.startForegroundService(this, intent)
        render()
    }

    private fun miningIntent(action: String): Intent = Intent(this, MiningService::class.java).apply {
        this.action = action
        putExtra(MiningService.EXTRA_WALLET, savedWallet())
        putExtra(MiningService.EXTRA_THREADS_PERCENT, threadPercent())
        putExtra(MiningService.EXTRA_THERMAL_THRESHOLD, thermal.progress)
        putExtra(MiningService.EXTRA_CHARGING_ONLY, chargingOnly.isChecked)
        putExtra(MiningService.EXTRA_WIFI_ONLY, wifiOnly.isChecked)
        putExtra(MiningService.EXTRA_QUIET_ON, quietHours.isChecked)
        putExtra(MiningService.EXTRA_QUIET_START, prefs.getInt(Prefs.QUIET_START, 22 * 60))
        putExtra(MiningService.EXTRA_QUIET_END, prefs.getInt(Prefs.QUIET_END, 7 * 60))
    }

    private fun sendReconfigure() {
        startService(miningIntent(MiningService.ACTION_RECONFIGURE))
    }

    private fun commitThreads(idx: Int) {
        if (MiningService.isRunning && idx != lastThreadIdx) {
            AlertDialog.Builder(this)
                .setTitle("Change threads?")
                .setMessage("XMRig restarts and re-benchmarks (~3–5 min) when thread % changes.")
                .setNegativeButton("Cancel") { _, _ -> snapThreadsBack() }
                .setPositiveButton("Change") { _, _ ->
                    lastThreadIdx = idx
                    save()
                    sendReconfigure()
                }
                .setOnCancelListener { snapThreadsBack() }
                .show()
        } else {
            lastThreadIdx = idx
            save()
        }
    }

    private fun snapThreadsBack() {
        applyingThreads = true
        threads.progress = lastThreadIdx
        applyingThreads = false
        updateLabels()
    }

    private fun ensureHarmConsent(onYes: () -> Unit) {
        if (prefs.getBoolean(Prefs.DEVICE_HARM, false)) {
            onYes()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Before you start mining")
            .setMessage(
                "Mining runs the CPU hard. That means heat and extra battery use.\n\n" +
                    "Anvil will:\n" +
                    "• Pause if the CPU or battery gets too hot\n" +
                    "• Optionally mine only while charging\n" +
                    "• Optionally mine only on Wi-Fi\n" +
                    "• Optionally pause during quiet hours\n\n" +
                    "No warranty. Use at your own risk.",
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("I understand") { _, _ ->
                prefs.edit().putBoolean(Prefs.DEVICE_HARM, true).apply()
                onYes()
            }
            .show()
    }

    private fun confirmRebench() {
        val running = MiningService.isRunning
        AlertDialog.Builder(this)
            .setTitle("Re-benchmark now?")
            .setMessage(
                if (running) {
                    "Restarts mining and re-runs the ~3–5 min CPU calibration."
                } else {
                    "Clears the cached algorithm. The next Start will re-calibrate (~3–5 min)."
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Re-benchmark") { _, _ ->
                if (running) {
                    startService(Intent(this, MiningService::class.java).setAction(MiningService.ACTION_RECALIBRATE))
                    Toast.makeText(this, "Re-benchmarking…", Toast.LENGTH_SHORT).show()
                } else {
                    XMRigManager.clearAlgoCache(this)
                    Toast.makeText(this, "Cache cleared. Start to re-benchmark.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun pasteWallet() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val addr = Wallet.parse(text)
        if (addr.isBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        wallet.setText(addr)
        walletError.visibility = View.GONE
    }

    private fun copyText(text: String, toast: String) {
        if (text.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("anvil", text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun requestScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openScanner() {
        scanWallet.launch(Intent(this, ScanActivity::class.java))
    }

    private fun openMoneroOcean() {
        val addr = savedWallet()
        if (!Wallet.isValid(addr)) {
            Toast.makeText(this, "Set a valid wallet first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://moneroocean.stream/#/wallet/$addr/overview")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun maybeHandleMoneroIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val addr = Wallet.parse(data)
        if (Wallet.isValid(addr)) {
            wallet.setText(addr)
            commitWallet()
        }
    }

    private fun savedWallet(): String =
        Wallet.parse(prefs.getString(Prefs.WALLET, "") ?: "")

    private fun enterWalletEdit(changing: Boolean) {
        editingWallet = true
        wallet.setText(if (changing) savedWallet() else "")
        walletError.visibility = View.GONE
        showWalletUi()
    }

    private fun commitWallet() {
        val addr = Wallet.parse(wallet.text?.toString().orEmpty())
        wallet.setText(addr)
        if (!Wallet.isValid(addr)) {
            walletError.visibility = View.VISIBLE
            walletError.text =
                "Invalid XMR address — 95 or 106 characters, starting with 4 or 8. Got ${addr.length}."
            return
        }
        walletError.visibility = View.GONE
        prefs.edit().putString(Prefs.WALLET, addr).apply()
        editingWallet = false
        showWalletUi()
        if (MiningService.isRunning) sendReconfigure()
        Toast.makeText(this, "Address saved", Toast.LENGTH_SHORT).show()
    }

    private fun showWalletUi() {
        val addr = savedWallet()
        val valid = Wallet.isValid(addr)
        val edit = editingWallet || !valid
        walletSaved.visibility = if (edit) View.GONE else View.VISIBLE
        walletEdit.visibility = if (edit) View.VISIBLE else View.GONE
        if (!edit) {
            walletDisplay.text = addr
        } else {
            walletEditTitle.text = if (valid) {
                "Change wallet"
            } else {
                "Enter your Monero (XMR) wallet address"
            }
            saveWallet.text = if (valid) "Save" else "Continue"
            cancelWallet.visibility = if (valid) View.VISIBLE else View.GONE
        }
        noWalletHint.visibility =
            if (!MiningService.isRunning && !valid) View.VISIBLE else View.GONE
    }

    private fun pickTime(start: Boolean) {
        val key = if (start) Prefs.QUIET_START else Prefs.QUIET_END
        val def = if (start) 22 * 60 else 7 * 60
        val current = prefs.getInt(key, def)
        TimePickerDialog(
            this,
            { _, hour, minute ->
                prefs.edit().putInt(key, hour * 60 + minute).apply()
                updateQuietButtons()
                if (MiningService.isRunning) sendReconfigure()
            },
            current / 60,
            current % 60,
            true,
        ).show()
    }

    private fun updateQuietButtons() {
        val s = prefs.getInt(Prefs.QUIET_START, 22 * 60)
        val e = prefs.getInt(Prefs.QUIET_END, 7 * 60)
        quietStartBtn.text = "Start ${QuietHours.format(s)}"
        quietEndBtn.text = "End ${QuietHours.format(e)}"
    }

    private fun checkUpdate() {
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
        } catch (_: Exception) {
            "0.1.0"
        }
        UpdateChecker.fetch { info ->
            if (info == null || !UpdateChecker.isNewer(info.versionName, current)) return@fetch
            updateBanner.visibility = View.VISIBLE
            updateBanner.text = "Anvil ${info.versionName} is out — tap to open GitHub Releases"
            updateBanner.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
            }
        }
    }

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
        val walletOk = Wallet.isValid(savedWallet())
        noWalletHint.visibility = if (!running && !walletOk) View.VISIBLE else View.GONE
        val canEditWallet = !running && !busy
        wallet.isEnabled = canEditWallet
        saveWallet.isEnabled = canEditWallet
        changeWallet.isEnabled = canEditWallet
        findViewById<ImageButton>(R.id.pasteWallet).isEnabled = canEditWallet
        findViewById<ImageButton>(R.id.scanWallet).isEnabled = canEditWallet
        threads.isEnabled = !busy
        thermal.isEnabled = !busy

        val pause = MiningState.pauseReason
        if (running && pause.isNotEmpty()) {
            pauseBanner.visibility = View.VISIBLE
            val extra = if (MiningState.noInternetSecondsLeft >= 0) {
                "  (pausing in ${MiningState.noInternetSecondsLeft}s)"
            } else {
                ""
            }
            pauseBanner.text = "Mining paused · $pause$extra"
        } else if (running && MiningState.noInternetSecondsLeft >= 0) {
            pauseBanner.visibility = View.VISIBLE
            pauseBanner.text = "No internet · pausing in ${MiningState.noInternetSecondsLeft}s"
        } else {
            pauseBanner.visibility = View.GONE
        }

        if (!cpuThermalOk) {
            val bt = MiningState.batteryTempC
            if (bt > 0) {
                batteryMeterLabel.text = "Battery temperature  ${bt}°C"
                batteryMeter.progress = bt.coerceIn(25, 42)
            }
        }

        if (!running) {
            hashrate.text = "— H/s"
            temp.text = "— °C"
            status.text = "Idle"
            if (log.text.isNotEmpty()) log.text = ""
            statsRow.visibility = View.GONE
            return
        }

        statsRow.visibility = View.VISIBLE
        accepted.text = MiningState.accepted.toString()
        rejected.text = MiningState.rejected.toString()

        hashrate.text = if (MiningState.hashrate10s > 0) {
            MiningState.formatHs(MiningState.hashrate10s)
        } else {
            "0 H/s"
        }
        temp.text = if (MiningState.tempCelsius > 0) {
            val src = if (MiningState.tempSource == "battery") "bat" else "cpu"
            "${MiningState.tempCelsius}°C $src"
        } else {
            "— °C"
        }
        status.text = when {
            pause.isNotEmpty() -> pause
            MiningState.poolError.isNotEmpty() && MiningState.pool.isEmpty() ->
                MiningState.poolError
            MiningState.pool.isNotEmpty() ->
                "${MiningState.pool}  ·  ${MiningState.accepted} accepted  ·  ${MiningState.rejected} rejected  ·  ${formatUptime(MiningState.uptimeSecs)}"
            else -> "Starting… first run may take a few minutes"
        }
        val live = MiningState.logText.ifBlank { MiningState.lastLogLine }
        if (log.text.toString() != live) {
            log.text = live
            logScroll.post { logScroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
        }
    }

    private fun updateLabels() {
        threadsLabel.text = "Threads  ${threadPercent()}%"
        thermalLabel.text = "CPU pause  ${thermal.progress}°C"
        alignThreadTicks()
    }

    /** Sit each 25/50/75/100 label under the SeekBar thumb, not in equal columns. */
    private fun alignThreadTicks() {
        if (!::threads.isInitialized || !::threadTicks.isInitialized) return
        if (threads.width == 0) return
        val pad = threads.paddingStart
        val track = (threads.width - threads.paddingStart - threads.paddingEnd).toFloat()
        val last = (threadSteps.size - 1).coerceAtLeast(1)
        threadTicks.forEachIndexed { i, label ->
            label.post {
                val center = pad + track * i / last
                val x = center - label.width / 2f
                val max = (threads.width - label.width).toFloat().coerceAtLeast(0f)
                label.translationX = x.coerceIn(0f, max)
            }
        }
    }

    private fun save() {
        prefs.edit()
            .putInt(Prefs.THREADS, threadPercent())
            .putInt(Prefs.THERMAL, thermal.progress)
            .putBoolean(Prefs.CHARGING_ONLY, chargingOnly.isChecked)
            .putBoolean(Prefs.WIFI_ONLY, wifiOnly.isChecked)
            .putBoolean(Prefs.QUIET_ON, quietHours.isChecked)
            .apply()
    }

    private fun formatUptime(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
