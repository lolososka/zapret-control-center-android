package io.github.dovecoteescapee.byedpi.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.AppUpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

class UpdateActivity : AppCompatActivity() {
    private lateinit var versionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var primaryButton: MaterialButton
    private lateinit var channelButton: MaterialButton
    private var release: AppUpdateRepository.Release? = null
    private var pendingInstall: File? = null
    private var awaitingChannelReturn = false
    private var work: Job? = null

    private val installPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstall ?: return@registerForActivityResult
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls()
            ) {
                openInstaller(apk)
            } else {
                showError(R.string.update_install_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_title)

        versionText = findViewById(R.id.update_version)
        statusText = findViewById(R.id.update_status)
        progress = findViewById(R.id.update_progress)
        primaryButton = findViewById(R.id.update_primary)
        channelButton = findViewById(R.id.update_channel)

        versionText.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        primaryButton.setOnClickListener { handlePrimaryAction() }
        channelButton.setOnClickListener { openChannel() }
        release = restoreRelease(savedInstanceState)
        awaitingChannelReturn = savedInstanceState?.getBoolean(STATE_AWAITING_CHANNEL) == true
        val restoredApk = savedInstanceState
            ?.getString(STATE_APK_PATH)
            ?.let(::File)
            ?.takeIf(File::isFile)
        val restoredRelease = release
        if (restoredRelease == null) {
            renderIdle()
        } else {
            renderAvailable(restoredRelease)
            if (restoredApk != null) {
                pendingInstall = restoredApk
                statusText.setText(R.string.update_ready_install)
                primaryButton.setText(R.string.update_install)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingChannelReturn) {
            awaitingChannelReturn = false
            release?.let { current ->
                gatePreferences().edit().putString(GATE_TAG_KEY, current.version).apply()
                renderAvailable(current)
            }
        } else if (pendingInstall != null && work?.isActive != true) {
            primaryButton.isEnabled = true
            primaryButton.setText(R.string.update_install)
        }
    }

    override fun onDestroy() {
        work?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        release?.let { current ->
            outState.putString(STATE_VERSION, current.version)
            outState.putString(STATE_APK_NAME, current.apkName)
            outState.putString(STATE_APK_URL, current.apkUrl)
            outState.putString(STATE_CHECKSUM_URL, current.checksumUrl)
            outState.putString(STATE_PAGE_URL, current.pageUrl)
            outState.putLong(STATE_SIZE, current.size)
            outState.putString(STATE_SHA256, current.sha256)
        }
        outState.putString(STATE_APK_PATH, pendingInstall?.absolutePath)
        outState.putBoolean(STATE_AWAITING_CHANNEL, awaitingChannelReturn)
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun handlePrimaryAction() {
        val current = release
        when {
            pendingInstall != null -> requestInstall(requireNotNull(pendingInstall))
            current == null -> checkForUpdate()
            !channelOpened(current) -> openChannel()
            else -> downloadAndInstall(current)
        }
    }

    private fun checkForUpdate() {
        if (work?.isActive == true) return
        setBusy(R.string.update_checking, indeterminate = true)
        work = lifecycleScope.launch {
            try {
                val latest = AppUpdateRepository.latest()
                release = latest
                if (latest == null) renderCurrent() else renderAvailable(latest)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Update check failed", error)
                showError(R.string.update_check_failed)
            }
        }
    }

    private fun renderIdle() {
        statusText.text = ""
        statusText.visibility = View.INVISIBLE
        progress.visibility = View.GONE
        primaryButton.isEnabled = true
        primaryButton.setText(R.string.update_check)
        channelButton.visibility = View.GONE
    }

    private fun downloadAndInstall(current: AppUpdateRepository.Release) {
        if (BuildConfig.DEBUG) {
            Toast.makeText(this, R.string.update_debug_build, Toast.LENGTH_LONG).show()
            return
        }
        if (work?.isActive == true) return
        progress.isIndeterminate = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        primaryButton.isEnabled = false
        channelButton.isEnabled = false
        statusText.setText(R.string.update_downloading)
        statusText.visibility = View.VISIBLE
        work = lifecycleScope.launch {
            try {
                val apk = AppUpdateRepository.download(this@UpdateActivity, current) { percent ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            progress.progress = percent
                            statusText.text = getString(R.string.update_downloading_progress, percent)
                        }
                    }
                }
                pendingInstall = apk
                progress.visibility = View.GONE
                channelButton.isEnabled = true
                requestInstall(apk)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Update download failed", error)
                showError(R.string.update_download_failed)
            }
        }
    }

    private fun requestInstall(apk: File) {
        val expectedVersion = release?.version ?: run {
            showError(R.string.update_download_failed)
            return
        }
        try {
            AppUpdateRepository.verifyApk(this, apk, expectedVersion)
        } catch (error: Exception) {
            Log.e(TAG, "Downloaded APK is no longer valid", error)
            pendingInstall = null
            showError(R.string.update_download_failed)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            statusText.setText(R.string.update_allow_install)
            primaryButton.isEnabled = true
            primaryButton.setText(R.string.update_open_settings)
            val opened = runCatching {
                installPermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    )
                )
            }.onFailure { error ->
                Log.e(TAG, "Cannot open install permission settings", error)
            }.isSuccess
            if (!opened) showError(R.string.update_install_failed)
            return
        }
        openInstaller(apk)
    }

    private fun openInstaller(apk: File) {
        runCatching {
            val expectedVersion = release?.version
                ?: throw IllegalStateException("Release state is missing")
            AppUpdateRepository.verifyApk(this, apk, expectedVersion)
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            statusText.setText(R.string.update_installing)
            primaryButton.isEnabled = true
            primaryButton.setText(R.string.update_install)
        }.onFailure { error ->
            Log.e(TAG, "Cannot open package installer", error)
            showError(R.string.update_install_failed)
        }
    }

    private fun renderCurrent() {
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.setText(R.string.update_current)
        versionText.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        primaryButton.isEnabled = true
        primaryButton.setText(R.string.update_check_again)
        channelButton.visibility = View.VISIBLE
        channelButton.isEnabled = true
    }

    private fun renderAvailable(current: AppUpdateRepository.Release) {
        pendingInstall = null
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.update_available, current.version)
        versionText.text = getString(
            R.string.update_version_change,
            BuildConfig.VERSION_NAME,
            current.version,
        )
        primaryButton.isEnabled = true
        primaryButton.setText(
            if (channelOpened(current)) R.string.update_download_install
            else R.string.update_open_channel,
        )
        channelButton.visibility = if (channelOpened(current)) View.VISIBLE else View.GONE
        channelButton.isEnabled = true
    }

    private fun setBusy(message: Int, indeterminate: Boolean) {
        statusText.visibility = View.VISIBLE
        statusText.setText(message)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = indeterminate
        primaryButton.isEnabled = false
        channelButton.visibility = View.GONE
    }

    private fun showError(message: Int) {
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.setText(message)
        primaryButton.isEnabled = true
        primaryButton.setText(R.string.update_retry)
        channelButton.visibility = View.VISIBLE
        channelButton.isEnabled = true
    }

    private fun openChannel() {
        awaitingChannelReturn = release != null
        val uri = Uri.parse(CHANNEL_URL)
        val telegram = Intent(Intent.ACTION_VIEW, uri).setPackage("org.telegram.messenger")
        val opened = runCatching {
            if (packageManager.resolveActivity(telegram, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(telegram)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }.isSuccess
        if (!opened) {
            awaitingChannelReturn = false
            Toast.makeText(this, R.string.update_channel_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun channelOpened(current: AppUpdateRepository.Release): Boolean =
        gatePreferences().getString(GATE_TAG_KEY, null) == current.version

    private fun gatePreferences() = getSharedPreferences(GATE_PREFERENCES, MODE_PRIVATE)

    private fun restoreRelease(state: Bundle?): AppUpdateRepository.Release? {
        state ?: return null
        val version = state.getString(STATE_VERSION) ?: return null
        val apkName = state.getString(STATE_APK_NAME) ?: return null
        val apkUrl = state.getString(STATE_APK_URL) ?: return null
        val checksumUrl = state.getString(STATE_CHECKSUM_URL) ?: return null
        val pageUrl = state.getString(STATE_PAGE_URL) ?: return null
        val sha256 = state.getString(STATE_SHA256) ?: return null
        val size = state.getLong(STATE_SIZE, -1L).takeIf { it > 0L } ?: return null
        return AppUpdateRepository.Release(
            version = version,
            apkName = apkName,
            apkUrl = apkUrl,
            checksumUrl = checksumUrl,
            pageUrl = pageUrl,
            size = size,
            sha256 = sha256,
        )
    }

    companion object {
        private const val TAG = "UpdateActivity"
        private const val CHANNEL_URL = "https://t.me/Slag0dworld"
        private const val GATE_PREFERENCES = "update_channel_gate"
        private const val GATE_TAG_KEY = "opened_for_version"
        private const val STATE_VERSION = "update.version"
        private const val STATE_APK_NAME = "update.apk_name"
        private const val STATE_APK_URL = "update.apk_url"
        private const val STATE_CHECKSUM_URL = "update.checksum_url"
        private const val STATE_PAGE_URL = "update.page_url"
        private const val STATE_SIZE = "update.size"
        private const val STATE_SHA256 = "update.sha256"
        private const val STATE_APK_PATH = "update.apk_path"
        private const val STATE_AWAITING_CHANNEL = "update.awaiting_channel"
    }
}
