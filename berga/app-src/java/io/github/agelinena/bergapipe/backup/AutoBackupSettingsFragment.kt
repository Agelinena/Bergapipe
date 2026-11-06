package io.github.agelinena.bergapipe.backup

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ThemeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ajustes → Backup e restauração → Backup automático. */
class AutoBackupSettingsFragment : PreferenceFragmentCompat() {
    private lateinit var enabledPref: SwitchPreferenceCompat
    private lateinit var destinationPref: Preference
    private lateinit var encryptPref: SwitchPreferenceCompat
    private lateinit var passwordPref: Preference
    private lateinit var statusPref: Preference

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            saveAndTest {
                it.copy(destination = BackupDestination.LOCAL_FOLDER, localTreeUri = uri.toString())
            }
        }
    }

    private val appContext: Context get() = requireContext().applicationContext

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        screen.title = getString(R.string.berga_backup_title)
        preferenceScreen = screen

        enabledPref = SwitchPreferenceCompat(context).apply {
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(R.string.berga_backup_enabled)
            setSummary(R.string.berga_backup_enabled_summary)
            setOnPreferenceChangeListener { _, value -> onEnabledChanged(value as Boolean) }
        }
        screen.addPreference(enabledPref)

        statusPref = plain(context, R.string.berga_backup_status).apply { isSelectable = false }
        screen.addPreference(statusPref)

        val destination = category(context, R.string.berga_backup_category_destination)
        screen.addPreference(destination)
        destinationPref = plain(context, R.string.berga_backup_destination).apply {
            setOnPreferenceClickListener { chooseDestination(); true }
        }
        destination.addPreference(destinationPref)

        val security = category(context, R.string.berga_backup_category_encryption)
        screen.addPreference(security)
        encryptPref = SwitchPreferenceCompat(context).apply {
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(R.string.berga_backup_encrypt)
            setSummary(R.string.berga_backup_encrypt_summary)
            setOnPreferenceChangeListener { _, value -> onEncryptChanged(value as Boolean) }
        }
        security.addPreference(encryptPref)
        passwordPref = plain(context, R.string.berga_backup_password).apply {
            setSummary(R.string.berga_backup_password_summary)
            setOnPreferenceClickListener { askPassword {}; true }
        }
        security.addPreference(passwordPref)

        val actions = category(context, R.string.berga_backup_category_actions)
        screen.addPreference(actions)
        actions.addPreference(plain(context, R.string.berga_backup_now).apply {
            setSummary(R.string.berga_backup_now_summary)
            setOnPreferenceClickListener { backupNow(); true }
        })
        actions.addPreference(plain(context, R.string.berga_backup_restore).apply {
            setSummary(R.string.berga_backup_restore_summary)
            setOnPreferenceClickListener { restore(); true }
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        ThemeHelper.setTitleToAppCompatActivity(activity, getString(R.string.berga_backup_title))
        refresh()
    }

    private fun refresh() {
        if (!::statusPref.isInitialized) return
        val config = BackupSettings.load(appContext)
        enabledPref.isChecked = config.enabled && config.isConfigured
        encryptPref.isChecked = config.encrypt
        passwordPref.isEnabled = config.encrypt
        destinationPref.summary = destinationSummary(config)

        val status = BackupSettings.status(appContext)
        val parts = mutableListOf<String>()
        parts += if (status.lastSuccessAt > 0) {
            getString(R.string.berga_backup_status_last, formatDate(status.lastSuccessAt))
        } else {
            getString(R.string.berga_backup_status_never)
        }
        if (status.lastError != null && status.lastErrorAt > status.lastSuccessAt) {
            parts += getString(R.string.berga_backup_status_failed, formatDate(status.lastErrorAt), status.lastError)
        }
        statusPref.summary = parts.joinToString("\n")
    }

    private fun destinationSummary(config: BackupConfig): String = when (config.destination) {
        BackupDestination.NONE -> getString(R.string.berga_backup_destination_none)
        BackupDestination.NEXTCLOUD -> getString(
            R.string.berga_backup_destination_nextcloud_summary, config.nextcloudUser, config.nextcloudServer
        )
        BackupDestination.S3 -> getString(
            R.string.berga_backup_destination_s3_summary, config.s3Bucket, config.s3Endpoint
        )
        BackupDestination.GOOGLE_DRIVE -> getString(
            if (config.driveRefreshToken.isNullOrEmpty()) R.string.berga_backup_destination_drive_pending
            else R.string.berga_backup_destination_drive_summary
        )
        BackupDestination.LOCAL_FOLDER -> getString(
            R.string.berga_backup_destination_local_summary,
            android.net.Uri.parse(config.localTreeUri ?: "").lastPathSegment ?: ""
        )
    }

    // ----- Liga/desliga -----

    private fun onEnabledChanged(enabled: Boolean): Boolean {
        val config = BackupSettings.load(appContext)
        if (enabled && !config.isConfigured) {
            if (config.encrypt && config.password.isNullOrEmpty() && config.destination != BackupDestination.NONE) {
                askPassword { onEnabledChanged(true); refresh() }
            } else {
                toast(R.string.berga_backup_configure_first)
                chooseDestination()
            }
            return false
        }
        val updated = BackupSettings.update(appContext) { it.copy(enabled = enabled) }
        BackupScheduler.apply(appContext, updated)
        refresh()
        return true
    }

    private fun onEncryptChanged(encrypt: Boolean): Boolean {
        val config = BackupSettings.load(appContext)
        if (encrypt && config.password.isNullOrEmpty()) {
            askPassword {
                BackupSettings.update(appContext) { it.copy(encrypt = true) }
                BackupScheduler.apply(appContext)
                refresh()
            }
            return false
        }
        BackupSettings.update(appContext) { it.copy(encrypt = encrypt) }
        BackupScheduler.apply(appContext)
        refresh()
        return true
    }

    private fun askPassword(onSaved: () -> Unit) {
        val context = requireContext()
        form(
            R.string.berga_backup_password, R.string.berga_backup_password_message,
            listOf(
                Field(R.string.berga_backup_password_field, "", secret = true),
                Field(R.string.berga_backup_password_confirm, "", secret = true),
            )
        ) { values ->
            when {
                values[0].length < 8 -> toast(R.string.berga_backup_password_short)
                values[0] != values[1] -> toast(R.string.berga_backup_password_mismatch)
                else -> {
                    BackupSettings.update(context.applicationContext) { it.copy(password = values[0]) }
                    toast(R.string.berga_backup_password_saved)
                    onSaved()
                }
            }
            refresh()
        }
    }

    // ----- Destinos -----

    private fun chooseDestination() {
        val options = arrayOf(
            getString(R.string.berga_backup_type_nextcloud),
            getString(R.string.berga_backup_type_s3),
            getString(R.string.berga_backup_type_drive),
            getString(R.string.berga_backup_type_local),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.berga_backup_destination)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> configureNextcloud()
                    1 -> configureS3()
                    2 -> configureDrive()
                    3 -> pickFolder.launch(null)
                }
            }
            .show()
    }

    private fun configureNextcloud() {
        val c = BackupSettings.load(appContext)
        form(
            R.string.berga_backup_type_nextcloud, R.string.berga_backup_nextcloud_message,
            listOf(
                Field(R.string.berga_backup_field_server, c.nextcloudServer, hint = "https://nuvem.exemplo.com"),
                Field(R.string.berga_backup_field_user, c.nextcloudUser),
                Field(R.string.berga_backup_field_app_password, "", secret = true, keep = !c.nextcloudPassword.isNullOrEmpty()),
                Field(R.string.berga_backup_field_folder, c.nextcloudFolder),
            )
        ) { v ->
            saveAndTest {
                it.copy(
                    destination = BackupDestination.NEXTCLOUD,
                    nextcloudServer = v[0], nextcloudUser = v[1],
                    nextcloudPassword = v[2].ifEmpty { it.nextcloudPassword },
                    nextcloudFolder = v[3].ifEmpty { "Bergapipe" },
                )
            }
        }
    }

    private fun configureS3() {
        val c = BackupSettings.load(appContext)
        form(
            R.string.berga_backup_type_s3, R.string.berga_backup_s3_message,
            listOf(
                Field(R.string.berga_backup_field_endpoint, c.s3Endpoint, hint = "s3.us-west-004.backblazeb2.com"),
                Field(R.string.berga_backup_field_region, c.s3Region, hint = getString(R.string.berga_backup_field_region_hint)),
                Field(R.string.berga_backup_field_bucket, c.s3Bucket),
                Field(R.string.berga_backup_field_prefix, c.s3Prefix),
                Field(R.string.berga_backup_field_key_id, c.s3KeyId),
                Field(R.string.berga_backup_field_secret_key, "", secret = true, keep = !c.s3Secret.isNullOrEmpty()),
            )
        ) { v ->
            saveAndTest {
                it.copy(
                    destination = BackupDestination.S3,
                    s3Endpoint = v[0], s3Region = v[1], s3Bucket = v[2], s3Prefix = v[3],
                    s3KeyId = v[4], s3Secret = v[5].ifEmpty { it.s3Secret },
                )
            }
        }
    }

    private fun configureDrive() {
        val c = BackupSettings.load(appContext)
        form(
            R.string.berga_backup_type_drive, R.string.berga_backup_drive_message,
            listOf(
                Field(R.string.berga_backup_field_client_id, c.driveClientId, hint = "….apps.googleusercontent.com"),
                Field(R.string.berga_backup_field_client_secret, "", secret = true, keep = !c.driveClientSecret.isNullOrEmpty()),
            ),
            positive = R.string.berga_backup_drive_sign_in,
        ) { v ->
            val clientId = v[0]
            if (clientId.isEmpty()) {
                toast(R.string.berga_backup_drive_client_missing)
                return@form
            }
            val secret = v[1].ifEmpty { c.driveClientSecret }
            BackupSettings.update(appContext) {
                it.copy(driveClientId = clientId, driveClientSecret = secret)
            }
            val ctx = appContext
            val progress = progress(R.string.berga_backup_drive_waiting)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { GoogleSignIn.signIn(ctx, clientId, secret) }
                }
                progress.dismiss()
                result.onSuccess { token ->
                    saveAndTest {
                        it.copy(
                            destination = BackupDestination.GOOGLE_DRIVE,
                            driveRefreshToken = token,
                            // Outra conta: a pasta antiga pode não existir nela.
                            driveFolderId = null,
                        )
                    }
                }.onFailure { showError(it) }
            }
        }
    }

    /** Salva a configuração, testa o acesso e liga o agendamento. */
    private fun saveAndTest(change: (BackupConfig) -> BackupConfig) {
        val ctx = appContext
        val config = BackupSettings.update(ctx, change)
        refresh()
        val progress = progress(R.string.berga_backup_testing)
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { BackupTargets.create(ctx, config).test() }.exceptionOrNull()
            }
            progress.dismiss()
            if (error == null) {
                toast(R.string.berga_backup_test_ok)
                BackupScheduler.apply(ctx)
            } else {
                showError(error)
            }
            refresh()
        }
    }

    // ----- Ações -----

    private fun backupNow() {
        val ctx = appContext
        if (!BackupSettings.load(ctx).isConfigured) {
            toast(R.string.berga_backup_configure_first)
            return
        }
        val progress = progress(R.string.berga_backup_running)
        lifecycleScope.launch {
            val result = runCatching { BackupRunner.run(ctx) }
            progress.dismiss()
            result.onSuccess {
                BackupNotifications.clear(ctx)
                Toast.makeText(ctx, getString(R.string.berga_backup_done, it), Toast.LENGTH_LONG).show()
            }.onFailure {
                BackupSettings.recordFailure(ctx, BackupRunner.errorMessage(it))
                showError(it)
            }
            refresh()
        }
    }

    private fun restore() {
        val ctx = appContext
        val config = BackupSettings.load(ctx)
        if (config.destination == BackupDestination.NONE) {
            toast(R.string.berga_backup_configure_first)
            return
        }
        val progress = progress(R.string.berga_backup_listing)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupTargets.create(ctx, config).list().sortedByDescending { it.name } }
            }
            progress.dismiss()
            val backups = result.getOrElse {
                showError(it)
                return@launch
            }
            if (backups.isEmpty()) {
                toast(R.string.berga_backup_none_found)
                return@launch
            }
            val labels = backups.map { backupLabel(it) }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.berga_backup_restore)
                .setItems(labels) { _, which -> confirmRestore(backups[which]) }
                .setNegativeButton(R.string.berga_cancel, null)
                .show()
        }
    }

    private fun confirmRestore(backup: RemoteBackup) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.berga_backup_restore_confirm_title)
            .setMessage(getString(R.string.berga_backup_restore_confirm_message, backupLabel(backup)))
            .setPositiveButton(R.string.berga_backup_restore_confirm) { _, _ ->
                downloadAndRestore(backup, BackupSettings.load(appContext).password)
            }
            .setNegativeButton(R.string.berga_cancel, null)
            .show()
    }

    private fun downloadAndRestore(backup: RemoteBackup, password: String?) {
        val ctx = appContext
        val progress = progress(R.string.berga_backup_restoring)
        lifecycleScope.launch {
            val result = runCatching {
                val zip = BackupRunner.download(ctx, backup, password)
                withContext(Dispatchers.IO) { BackupArchive.restore(ctx, zip) }
            }
            progress.dismiss()
            result.onSuccess {
                Toast.makeText(ctx, R.string.berga_backup_restored, Toast.LENGTH_LONG).show()
                NavigationHelper.restartApp(requireActivity())
            }.onFailure { error ->
                if (error is WrongPasswordException) {
                    form(
                        R.string.berga_backup_password, R.string.berga_backup_password_other_message,
                        listOf(Field(R.string.berga_backup_password_field, "", secret = true)),
                    ) { v -> if (v[0].isNotEmpty()) downloadAndRestore(backup, v[0]) }
                } else {
                    showError(error)
                }
            }
        }
    }

    private fun backupLabel(backup: RemoteBackup): String {
        val stamp = backup.name.removePrefix(BackupArchive.PREFIX).take(15)
        val date = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(stamp)!!.time
        }.getOrDefault(backup.modifiedAt)
        val lock = if (BackupArchive.isEncryptedName(backup.name)) " 🔒" else ""
        val size = if (backup.size > 0) " · " + Formatter.formatShortFileSize(appContext, backup.size) else ""
        return formatDate(date) + size + lock
    }

    // ----- Utilidades de interface -----

    private class Field(
        val label: Int,
        val initial: String,
        val secret: Boolean = false,
        val hint: String? = null,
        /** Já existe um valor guardado: em branco mantém o atual. */
        val keep: Boolean = false,
    )

    private fun form(
        title: Int,
        message: Int?,
        fields: List<Field>,
        positive: Int = R.string.berga_save,
        onSave: (List<String>) -> Unit,
    ) {
        val context = requireContext()
        val pad = dp(20)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        if (message != null) {
            column.addView(TextView(context).apply {
                setText(message)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(12))
            })
        }
        val inputs = fields.map { field ->
            column.addView(TextView(context).apply {
                setText(field.label)
                setPadding(0, dp(8), 0, 0)
            })
            EditText(context).apply {
                setSingleLine()
                setText(field.initial)
                hint = when {
                    field.keep -> getString(R.string.berga_backup_field_keep)
                    else -> field.hint
                }
                inputType = if (field.secret) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                column.addView(this)
            }
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(column) })
            .setPositiveButton(positive) { _, _ -> onSave(inputs.map { it.text.toString().trim() }) }
            .setNegativeButton(R.string.berga_cancel, null)
            .show()
    }

    private fun progress(message: Int): AlertDialog {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                setText(message)
                setPadding(dp(16), 0, 0, 0)
            })
        }
        return AlertDialog.Builder(context).setView(row).setCancelable(false).show()
    }

    private fun showError(error: Throwable) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.berga_backup_error_title)
            .setMessage(BackupRunner.errorMessage(error))
            .setPositiveButton(R.string.berga_ok, null)
            .show()
    }

    private fun plain(context: Context, title: Int) = Preference(context).apply {
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setTitle(title)
    }

    private fun category(context: Context, title: Int) = PreferenceCategory(context).apply {
        isIconSpaceReserved = false
        setTitle(title)
    }

    private fun formatDate(time: Long): String =
        DateFormat.getMediumDateFormat(appContext).format(Date(time)) + " " +
            DateFormat.getTimeFormat(appContext).format(Date(time))

    private fun toast(text: Int) = Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
