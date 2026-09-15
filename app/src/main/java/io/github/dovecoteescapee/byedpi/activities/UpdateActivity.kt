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
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.AppUpdateRepository
import io.github.dovecoteescapee.byedpi.core.UpdateMembershipRepository
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
    private var work: Job? = null
    private lateinit var access: UpdateAccessViewModel

    private val hasVerifiedSubscription: Boolean
        get() = release?.let { access.grant?.isValid(it.version) } == true

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
        access = ViewModelProvider(this)[UpdateAccessViewModel::class.java]
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
        channelButton.setOnClickListener {
            val session = access.session
            if (session != null && release?.let { session.isValid(it.version) } == true) {
                openBot(session)
            } else openChannel()
        }
        release = restoreRelease(savedInstanceState)
        val restoredApk = savedInstanceState
            ?.getString(STATE_APK_PATH)
            ?.let(::File)
            ?.takeIf { apk ->
                runCatching {
                    apk.isFile && apk.canonicalFile.parentFile == File(cacheDir, "updates").canonicalFile
                }.getOrDefault(false)
            }
        val restoredRelease = release
        if (restoredRelease == null) {
            renderIdle()
        } else {
            renderAvailable(restoredRelease)
            if (restoredApk != null) {
                pendingInstall = restoredApk
                renderAvailable(restoredRelease)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (access.awaitingBotReturn && work?.isActive != true && release != null) {
            access.awaitingBotReturn = false
            verifyMembership()
        } else if (pendingInstall != null && work?.isActive != true) {
            primaryButton.isEnabled = true
            primaryButton.setText(
                if (hasVerifiedSubscription) R.string.update_install
                else R.string.update_membership_confirm,
            )
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
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun handlePrimaryAction() {
        val current = release
        when {
            current == null -> checkForUpdate()
            !hasVerifiedSubscription -> {
                if (access.session?.isValid(current.version) == true) verifyMembership()
                else if (UpdateMembershipRepository.isConfigured()) confirmMembership()
                else openChannel()
            }
            pendingInstall != null -> requestInstall(requireNotNull(pendingInstall))
            else -> downloadAndInstall(current)
        }
    }

    private fun checkForUpdate() {
        if (work?.isActive == true) return
        setBusy(R.string.update_checking, indeterminate = true)
        work = lifecycleScope.launch {
            try {
                val latest = AppUpdateRepository.latest()
                if (latest?.version != release?.version) {
                    access.clear()
                    pendingInstall = null
                }
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

    private fun confirmMembership() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_membership_title)
            .setMessage(R.string.update_membership_privacy)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_membership_continue) { _, _ -> beginMembership() }
            .show()
    }

    private fun beginMembership() {
        val current = release ?: return
        if (work?.isActive == true) return
        access.clear()
        setBusy(R.string.update_membership_connecting, indeterminate = true)
        work = lifecycleScope.launch {
            try {
                val session = UpdateMembershipRepository.create(current.version)
                access.session = session
                renderAvailable(current)
                openBot(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: UpdateMembershipRepository.RateLimited) {
                showMembershipCooldown(error.retryAfterSeconds)
            } catch (_: Exception) {
                // Authentication bodies, URLs and tokens must never enter exported diagnostics.
                showError(R.string.update_membership_failed)
            }
        }
    }

    private fun verifyMembership() {
        val current = release ?: return
        val session = access.session ?: run {
            renderAvailable(current)
            return
        }
        if (work?.isActive == true) return
        access.awaitingBotReturn = false
        access.grant = null
        setBusy(R.string.update_membership_checking, indeterminate = true)
        work = lifecycleScope.launch {
            try {
                val check = UpdateMembershipRepository.authorize(session)
                access.grant = check.grant
                renderAvailable(current)
                when (check.status) {
                    UpdateMembershipRepository.Status.Pending ->
                        statusText.setText(R.string.update_membership_waiting)
                    UpdateMembershipRepository.Status.NotMember ->
                        statusText.setText(R.string.update_membership_not_member)
                    UpdateMembershipRepository.Status.Verified -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: UpdateMembershipRepository.SessionExpired) {
                access.clear()
                showError(R.string.update_membership_expired)
            } catch (error: UpdateMembershipRepository.RateLimited) {
                showMembershipCooldown(error.retryAfterSeconds)
            } catch (_: Exception) {
                access.grant = null
                showError(R.string.update_membership_failed)
            }
        }
    }

    private fun openBot(session: UpdateMembershipRepository.Session) {
        access.awaitingBotReturn = true
        if (!openTelegramLink(session.botUrl)) {
            access.awaitingBotReturn = false
            Toast.makeText(this, R.string.update_channel_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMembershipCooldown(seconds: Long) {
        showError(R.string.update_membership_failed)
        statusText.text = getString(R.string.update_membership_cooldown, seconds)
    }

    private fun downloadAndInstall(current: AppUpdateRepository.Release) {
        if (!hasVerifiedSubscription) {
            showError(R.string.update_subscription_unavailable)
            return
        }
        if (BuildConfig.DEBUG) {
            Toast.makeText(this, R.string.update_debug_build, Toast.LENGTH_LONG).show()
            return
        }
        if (work?.isActive == true) return
        val session = access.session ?: run {
            showError(R.string.update_membership_expired)
            return
        }
        access.grant = null
        progress.isIndeterminate = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        primaryButton.isEnabled = false
        channelButton.isEnabled = false
        statusText.setText(R.string.update_downloading)
        statusText.visibility = View.VISIBLE
        work = lifecycleScope.launch {
            try {
                val check = UpdateMembershipRepository.authorize(session)
                access.grant = check.grant
                if (check.status != UpdateMembershipRepository.Status.Verified) {
                    renderAvailable(current)
                    statusText.setText(
                        if (check.status == UpdateMembershipRepository.Status.NotMember)
                            R.string.update_membership_not_member else R.string.update_membership_waiting,
                    )
                    return@launch
                }
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
            } catch (_: UpdateMembershipRepository.SessionExpired) {
                access.clear()
                showError(R.string.update_membership_expired)
            } catch (error: UpdateMembershipRepository.RateLimited) {
                access.grant = null
                showMembershipCooldown(error.retryAfterSeconds)
            } catch (error: Exception) {
                // This path can include authentication; don't export URL/header exceptions.
                access.grant = null
                showError(R.string.update_download_failed)
            }
        }
    }

    private fun requestInstall(apk: File) {
        if (!hasVerifiedSubscription) {
            showError(R.string.update_subscription_unavailable)
            return
        }
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
        if (!hasVerifiedSubscription) {
            showError(R.string.update_subscription_unavailable)
            return
        }
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
        progress.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        val hasSession = access.session?.isValid(current.version) == true
        val configured = UpdateMembershipRepository.isConfigured()
        statusText.setText(when {
            hasVerifiedSubscription && pendingInstall != null -> R.string.update_ready_install
            hasVerifiedSubscription -> R.string.update_membership_verified
            hasSession -> R.string.update_membership_waiting
            configured -> R.string.update_membership_required
            else -> R.string.update_subscription_unavailable
        })
        versionText.text = getString(
            R.string.update_version_change,
            BuildConfig.VERSION_NAME,
            current.version,
        )
        primaryButton.isEnabled = true
        primaryButton.setText(when {
            hasVerifiedSubscription && pendingInstall != null -> R.string.update_install
            hasVerifiedSubscription -> R.string.update_download_install
            hasSession -> R.string.update_membership_check
            configured -> R.string.update_membership_confirm
            else -> R.string.update_open_channel
        })
        channelButton.setText(if (hasSession) R.string.update_open_bot else R.string.update_channel)
        channelButton.visibility = if (configured) View.VISIBLE else View.GONE
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
        channelButton.setText(
            if (access.session?.let { session -> release?.let { session.isValid(it.version) } } == true)
                R.string.update_open_bot else R.string.update_channel,
        )
    }

    private fun openChannel() {
        if (!openTelegramLink(CHANNEL_URL)) {
            Toast.makeText(this, R.string.update_channel_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTelegramLink(url: String): Boolean {
        val uri = Uri.parse(url)
        val telegram = Intent(Intent.ACTION_VIEW, uri).setPackage("org.telegram.messenger")
        return runCatching {
            if (packageManager.resolveActivity(telegram, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(telegram)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }.isSuccess
    }

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
        private const val STATE_VERSION = "update.version"
        private const val STATE_APK_NAME = "update.apk_name"
        private const val STATE_APK_URL = "update.apk_url"
        private const val STATE_CHECKSUM_URL = "update.checksum_url"
        private const val STATE_PAGE_URL = "update.page_url"
        private const val STATE_SIZE = "update.size"
        private const val STATE_SHA256 = "update.sha256"
        private const val STATE_APK_PATH = "update.apk_path"
    }
}
