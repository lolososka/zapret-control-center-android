package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.fragments.MainSettingsFragment
import io.github.dovecoteescapee.byedpi.databinding.ActivityMainBinding
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.TelegramWsProxyService
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var metricsJob: Job? = null
    private var pingJob: Job? = null
    private var strategyProbeJob: Job? = null
    private var telegramStartJob: Job? = null
    private var connectionStartedAt = 0L
    private var rxBaseline = 0L
    private var txBaseline = 0L
    @Volatile private var lastPingMs: Long? = null
    private var pendingMode: Mode? = null
    private var pendingServiceRestart = false
    private var restartAfterProfile = false
    private var lastVisualStatus: AppStatus? = null
    private var lastVisualMode: Mode? = null

    private val pressInInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val pressOutInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(minimal: Boolean): String? =
            try {
                if (minimal) {
                    "Zapret Mobile ${BuildConfig.VERSION_NAME}\n" +
                        "Краткая диагностика: системный журнал не экспортирован.\n"
                } else {
                    Runtime.getRuntime()
                        .exec("logcat *:D -d")
                        .inputStream.bufferedReader()
                        .use { it.readText() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch {
                val minimal = getPreferences().getBoolean("privacy_minimal_logs", true)
                val logs = withContext(Dispatchers.IO) { collectLogs(minimal) }

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = it.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use {
                            try {
                                it.write(logs.toByteArray())
                            } catch (e: IOException) {
                                Log.e(TAG, "Failed to save logs", e)
                            }
                        } ?: run {
                            Log.e(TAG, "Failed to open output stream")
                        }
                    }
                }
            }
        }

    private val profilePickerRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val shouldRestart = restartAfterProfile && appStatus.first == AppStatus.Running
            restartAfterProfile = false
            if (result.resultCode == RESULT_OK) {
                updateStatus()
                val preferences = getPreferences()
                val autoProbeRequested =
                    StrategyProfiles.selected(preferences) == StrategyProfiles.Profile.Auto &&
                        preferences.mode() == Mode.Proxy
                if (autoProbeRequested) {
                    pendingServiceRestart = false
                    if (appStatus.first == AppStatus.Running) stop()
                    startAutoStrategyProbe()
                } else if (shouldRestart) {
                    pendingServiceRestart = true
                    Toast.makeText(
                        this,
                        R.string.strategy_applied_restart,
                        Toast.LENGTH_SHORT,
                    ).show()
                    stop()
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (debugLoggingEnabled()) Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                if (debugLoggingEnabled()) Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                if (debugLoggingEnabled()) Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> {
                    updateStatus()
                    if (action == STOPPED_BROADCAST) {
                        val target = pendingMode
                        pendingMode = null
                        val restart = pendingServiceRestart
                        pendingServiceRestart = false
                        if (target != null) {
                            getPreferences().edit()
                                .putString("byedpi_mode", target.name.lowercase(Locale.ROOT))
                                .apply()
                            updateStatus()
                        }
                        if (target != null || restart) {
                            start()
                        }
                    }
                }

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> if (debugLoggingEnabled()) Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        ContextCompat.registerReceiver(
            this,
            receiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        binding.statusButton.setOnClickListener {
            toggleConnection()
        }

        binding.routeDial.setOnClickListener { toggleConnection() }
        binding.routeDial.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> animateDialPress(view, pressed = true)

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> animateDialPress(view, pressed = false)
            }
            false
        }
        binding.modeRow.setOnClickListener { toggleMode() }
        binding.settingsButton.setOnClickListener { openSettings() }
        binding.strategyBadge.setOnClickListener {
            restartAfterProfile = appStatus.first == AppStatus.Running
            profilePickerRegister.launch(Intent(this, StrategyPickerActivity::class.java))
        }
        binding.telegramSetupButton.setOnClickListener { showTelegramSetup() }
        binding.saveLogsButton.setOnClickListener { saveLogs() }

        val theme = getPreferences()
            .getString("app_theme", null)
        MainSettingsFragment.setTheme(theme ?: "dark")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        metricsJob?.cancel()
        pingJob?.cancel()
        strategyProbeJob?.cancel()
        telegramStartJob?.cancel()
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings(status)
                true
            }

            R.id.action_save_logs -> {
                saveLogs()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun toggleConnection() {
        when (appStatus.first) {
            AppStatus.Halted -> start()
            AppStatus.Running -> stop()
        }
    }

    private fun toggleMode() {
        val current = getPreferences().mode()
        val target = if (current == Mode.VPN) Mode.Proxy else Mode.VPN
        if (appStatus.first == AppStatus.Running) {
            pendingMode = target
            stop()
        } else {
            getPreferences().edit()
                .putString("byedpi_mode", target.name.lowercase(Locale.ROOT))
                .apply()
            updateStatus()
        }
    }

    private fun openSettings(status: AppStatus = appStatus.first) {
        if (status == AppStatus.Halted) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLogs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "zapret-mobile-diagnostics.txt")
        }

        logsRegister.launch(intent)
    }

    private fun startAutoStrategyProbe() {
        if (strategyProbeJob?.isActive == true) return

        strategyProbeJob = lifecycleScope.launch {
            Toast.makeText(
                this@MainActivity,
                R.string.strategy_auto_checking,
                Toast.LENGTH_SHORT,
            ).show()

            if (!waitForStatus(AppStatus.Halted, 4_000L)) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.strategy_auto_waiting,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            val preferences = getPreferences()
            val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
            val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
            val candidates = listOf(
                StrategyProfiles.Profile.Messaging,
                StrategyProfiles.Profile.Balanced,
                StrategyProfiles.Profile.Strong,
                StrategyProfiles.Profile.Games,
            )

            var selected: StrategyProfiles.Profile? = null
            for (candidate in candidates) {
                if (!startProxyCandidate(candidate)) continue
                if (probeTelegramEndpoint(proxyIp, proxyPort)) {
                    selected = candidate
                    break
                }
            }

            if (selected == null) {
                val fallback = StrategyProfiles.Profile.Messaging
                restartProxyCandidate(fallback)
                Toast.makeText(
                    this@MainActivity,
                    R.string.strategy_auto_failed,
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                StrategyProfiles.apply(preferences, selected)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.strategy_auto_selected, getString(selected.title)),
                    Toast.LENGTH_LONG,
                ).show()
            }
            updateStatus()
        }
    }

    private suspend fun startProxyCandidate(profile: StrategyProfiles.Profile): Boolean {
        val preferences = getPreferences()
        StrategyProfiles.apply(preferences, profile)
        if (appStatus.first == AppStatus.Running) {
            ServiceManager.stop(this)
            if (!waitForStatus(AppStatus.Halted, 4_000L)) return false
        }
        ServiceManager.start(this, Mode.Proxy)
        return waitForStatus(AppStatus.Running, 5_000L)
    }

    private suspend fun restartProxyCandidate(profile: StrategyProfiles.Profile) {
        startProxyCandidate(profile)
    }

    private suspend fun waitForStatus(target: AppStatus, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (appStatus.first == target) return true
            delay(100L)
        }
        return appStatus.first == target
    }

    private suspend fun probeTelegramEndpoint(proxyIp: String, proxyPort: String): Boolean =
        withContext(Dispatchers.IO) {
            val port = proxyPort.toIntOrNull() ?: return@withContext false
            listOf("telegram.org", "api.telegram.org").any { host ->
                try {
                    Socket().use { socket ->
                        socket.soTimeout = 2_000
                        socket.connect(InetSocketAddress(proxyIp, port), 2_000)
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()

                        output.write(byteArrayOf(0x05, 0x01, 0x00))
                        output.flush()
                        val greeting = ByteArray(2)
                        if (!readFully(input, greeting) ||
                            greeting[0] != 0x05.toByte() ||
                            greeting[1] != 0x00.toByte()
                        ) {
                            return@use false
                        }

                        val hostBytes = host.toByteArray(Charsets.US_ASCII)
                        val request = ByteArray(7 + hostBytes.size)
                        request[0] = 0x05
                        request[1] = 0x01
                        request[2] = 0x00
                        request[3] = 0x03
                        request[4] = hostBytes.size.toByte()
                        hostBytes.copyInto(request, destinationOffset = 5)
                        request[5 + hostBytes.size] = 0x01
                        request[6 + hostBytes.size] = 0xBB.toByte()
                        output.write(request)
                        output.flush()

                        val response = ByteArray(4)
                        readFully(input, response) &&
                            response[0] == 0x05.toByte() &&
                            response[1] == 0x00.toByte()
                    }
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun showTelegramSetup() {
        val running = TelegramWsProxyService.running.value
        val link = telegramProxyLink()
        AlertDialog.Builder(this)
            .setTitle(R.string.telegram_setup_title)
            .setMessage(getString(if (running) R.string.telegram_ws_setup_running else R.string.telegram_ws_setup_stopped, link))
            .setNeutralButton(R.string.telegram_copy_proxy) { _, _ -> copyTelegramProxy(link) }
            .setNegativeButton(if (running) R.string.telegram_ws_stop else R.string.telegram_open_app) { _, _ ->
                if (running) {
                    TelegramWsProxyService.stop(this)
                    updateStatus()
                } else {
                    openTelegram()
                }
            }
            .setPositiveButton(if (running) R.string.telegram_add_proxy else R.string.telegram_ws_start) { _, _ ->
                if (running) {
                    openTelegramProxyLink(link)
                } else {
                    startTelegramAndApply(link)
                }
            }
            .show()
    }

    private fun startTelegramAndApply(link: String) {
        try {
            TelegramWsProxyService.start(this)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to start Telegram proxy service", error)
            Toast.makeText(this, R.string.telegram_ws_failed, Toast.LENGTH_LONG).show()
            updateStatus()
            return
        }

        Toast.makeText(this, R.string.telegram_ws_starting, Toast.LENGTH_SHORT).show()
        telegramStartJob?.cancel()
        telegramStartJob = lifecycleScope.launch {
            if (TelegramWsProxyService.awaitReady()) {
                updateStatus()
                openTelegramProxyLink(link)
            } else {
                updateStatus()
                Toast.makeText(
                    this@MainActivity,
                    R.string.telegram_ws_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun telegramProxyLink(): String {
        val secret = TelegramWsProxyService.secretForLink(this)
        return Uri.Builder()
            .scheme("https")
            .authority("t.me")
            .appendPath("proxy")
            .appendQueryParameter("server", "127.0.0.1")
            .appendQueryParameter("port", "1443")
            .appendQueryParameter("secret", secret)
            .build()
            .toString()
    }

    private fun copyTelegramProxy(link: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Telegram MTProto WS", link))
        Toast.makeText(this, R.string.telegram_proxy_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openTelegram() {
        val launchIntent = packageManager.getLaunchIntentForPackage("org.telegram.messenger")
            ?: packageManager.getLaunchIntentForPackage("org.telegram.messenger.web")
            ?: packageManager.getLaunchIntentForPackage("org.thunderdog.challegram")
        if (launchIntent == null) {
            Toast.makeText(this, R.string.telegram_not_installed, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(launchIntent)
        }
    }

    private fun openTelegramProxyLink(link: String) {
        val httpsUri = Uri.parse(link)
        val tgUri = Uri.Builder()
            .scheme("tg")
            .authority("proxy")
            .appendQueryParameter("server", httpsUri.getQueryParameter("server") ?: "127.0.0.1")
            .appendQueryParameter("port", httpsUri.getQueryParameter("port") ?: "1443")
            .appendQueryParameter("secret", httpsUri.getQueryParameter("secret") ?: "")
            .build()
        val packageCandidates = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
        )
        for (uri in listOf(tgUri, httpsUri)) {
            for (packageName in packageCandidates) {
                val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
                if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    val launched = runCatching { startActivity(intent) }.isSuccess
                    if (launched) {
                        Toast.makeText(this, R.string.telegram_proxy_opened, Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }
        }

        // A small number of Telegram builds do not expose package metadata to
        // queries. Try the Telegram deep link before falling back to HTTPS.
        for (uri in listOf(tgUri, httpsUri)) {
            val genericIntent = Intent(Intent.ACTION_VIEW, uri)
            if (packageManager.resolveActivity(genericIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                val launched = runCatching { startActivity(genericIntent) }.isSuccess
                if (launched) {
                    Toast.makeText(this, R.string.telegram_proxy_opened, Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        openTelegram()
        Toast.makeText(this, R.string.telegram_proxy_manual_fallback, Toast.LENGTH_LONG).show()
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        if (debugLoggingEnabled()) Log.d(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val selectedMode = preferences.mode()
        val proxyIp = preferences.getStringNotNull("byedpi_proxy_ip", "127.0.0.1")
        val proxyPort = preferences.getStringNotNull("byedpi_proxy_port", "1080")
        binding.proxyAddress.text = getString(R.string.proxy_address, proxyIp, proxyPort)
        binding.telegramProxyAddress.text = getString(R.string.telegram_proxy_address, "127.0.0.1", "1443")
        binding.telegramProxyStatus.setText(
            when {
                TelegramWsProxyService.running.value -> R.string.telegram_proxy_ready
                TelegramWsProxyService.starting.value -> R.string.telegram_proxy_starting
                else -> R.string.telegram_proxy_off
            }
        )
        binding.modeValue.setText(
            when (selectedMode) {
                Mode.VPN -> R.string.mode_vpn_value
                Mode.Proxy -> R.string.mode_proxy_value
            }
        )
        binding.strategyBadge.text = getString(
            R.string.strategy_badge,
            StrategyProfiles.title(this, preferences),
        )
        binding.routeDial.contentDescription = getString(R.string.dial_toggle)

        when (status) {
            AppStatus.Halted -> {
                binding.routeDial.setRunning(false)
                binding.routeDial.contentDescription = getString(R.string.dial_toggle)
                binding.statusDetail.setText(R.string.status_ready)
                binding.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.dial_quiet_strong)
                )
                when (selectedMode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                        binding.statusButton.setText(R.string.vpn_connect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                        binding.statusButton.setText(R.string.proxy_start)
                    }
                }
                binding.statusButton.isEnabled = true
                stopMetricsLoop()
            }

            AppStatus.Running -> {
                binding.routeDial.setRunning(true)
                binding.routeDial.contentDescription = getString(R.string.dial_toggle)
                binding.statusDetail.setText(R.string.status_running_local)
                binding.statusDot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.zapret_violet_soft)
                )
                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                        binding.statusButton.setText(R.string.vpn_disconnect)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                        binding.statusButton.setText(R.string.proxy_stop)
                    }
                }
                binding.statusButton.isEnabled = true
                startMetricsLoop()
            }
        }

        val stateChanged = lastVisualStatus != null &&
            (lastVisualStatus != status || lastVisualMode != selectedMode)
        lastVisualStatus = status
        lastVisualMode = selectedMode
        if (stateChanged) animateConnectionState()
    }

    private fun animateDialPress(view: View, pressed: Boolean) {
        view.animate().cancel()
        if (!uiAnimationsEnabled()) {
            view.scaleX = if (pressed) 0.97f else 1f
            view.scaleY = if (pressed) 0.97f else 1f
            view.alpha = if (pressed) 0.94f else 1f
            return
        }

        view.animate()
            .scaleX(if (pressed) 0.97f else 1f)
            .scaleY(if (pressed) 0.97f else 1f)
            .alpha(if (pressed) 0.94f else 1f)
            .setDuration(if (pressed) 150L else 240L)
            .setInterpolator(if (pressed) pressInInterpolator else pressOutInterpolator)
            .start()
    }

    private fun animateConnectionState() {
        val views = listOf(
            binding.statusText,
            binding.statusDetail,
            binding.modeValue,
            binding.strategyBadge,
        )

        if (!uiAnimationsEnabled()) {
            views.forEach {
                it.alpha = 1f
                it.translationY = 0f
            }
            binding.statusDot.alpha = 1f
            binding.statusDot.scaleX = 1f
            binding.statusDot.scaleY = 1f
            return
        }

        views.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = dp(6f)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 24L)
                .setDuration(280L)
                .setInterpolator(pressInInterpolator)
                .start()
        }

        binding.statusDot.animate().cancel()
        binding.statusDot.alpha = 0.4f
        binding.statusDot.scaleX = 0.72f
        binding.statusDot.scaleY = 0.72f
        binding.statusDot.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(pressInInterpolator)
            .start()
    }

    private fun startMetricsLoop() {
        if (connectionStartedAt == 0L) {
            connectionStartedAt = SystemClock.elapsedRealtime()
            rxBaseline = supportedBytes(TrafficStats.getTotalRxBytes())
            txBaseline = supportedBytes(TrafficStats.getTotalTxBytes())
            lastPingMs = null
        }
        if (metricsJob?.isActive == true) return
        metricsJob = lifecycleScope.launch {
            var nextPingAt = 0L
            while (isActive && appStatus.first == AppStatus.Running) {
                val now = SystemClock.elapsedRealtime()
                updateMetrics(now)
                if (now >= nextPingAt && pingJob?.isActive != true) {
                    nextPingAt = now + 5_000L
                    pingJob = launch(Dispatchers.IO) {
                        lastPingMs = measurePing()
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun stopMetricsLoop() {
        metricsJob?.cancel()
        pingJob?.cancel()
        metricsJob = null
        pingJob = null
        connectionStartedAt = 0L
        lastPingMs = null
        binding.connectionUptime.text = getString(
            R.string.connection_uptime,
            "00:00:00",
            getString(R.string.metric_unavailable),
        )
        binding.connectionTraffic.text = getString(
            R.string.connection_traffic,
            "0 Б",
            "0 Б",
        )
    }

    private fun updateMetrics(now: Long) {
        val elapsed = (now - connectionStartedAt).coerceAtLeast(0L) / 1_000L
        val hours = elapsed / 3_600L
        val minutes = (elapsed % 3_600L) / 60L
        val seconds = elapsed % 60L
        val uptime = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        val ping = lastPingMs?.let { "$it мс" } ?: getString(R.string.metric_unavailable)
        val rx = (supportedBytes(TrafficStats.getTotalRxBytes()) - rxBaseline).coerceAtLeast(0L)
        val tx = (supportedBytes(TrafficStats.getTotalTxBytes()) - txBaseline).coerceAtLeast(0L)
        binding.connectionUptime.text = getString(R.string.connection_uptime, uptime, ping)
        binding.connectionTraffic.text = getString(
            R.string.connection_traffic,
            formatBytes(rx),
            formatBytes(tx),
        )
    }

    private fun measurePing(): Long? {
        val socket = Socket()
        val started = SystemClock.elapsedRealtime()
        return try {
            socket.connect(InetSocketAddress("1.1.1.1", 443), 900)
            SystemClock.elapsedRealtime() - started
        } catch (_: IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun supportedBytes(value: Long): Long =
        if (value == TrafficStats.UNSUPPORTED.toLong() || value < 0L) 0L else value

    private fun uiAnimationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun debugLoggingEnabled(): Boolean =
        !getPreferences().getBoolean("privacy_minimal_logs", true)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun formatBytes(value: Long): String = when {
        value < 1_024L -> "$value Б"
        value < 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f КБ", value / 1_024f)
        value < 1_024L * 1_024L * 1_024L ->
            String.format(Locale.ROOT, "%.1f МБ", value / (1_024f * 1_024f))
        else -> String.format(Locale.ROOT, "%.2f ГБ", value / (1_024f * 1_024f * 1_024f))
    }
}
