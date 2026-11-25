package top.niunaijun.blackboxa.zenix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.BuildConfig
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityZenixBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity
import java.io.File

/**
 * Zenix Container: the dual-space screen.
 *
 * Layout, top to bottom:
 *  - host/payload status (what this build can actually do, measured at open);
 *  - actions: self test, new space, install APK into space 0, share the log;
 *  - the space list, each row carrying its own microG stack state;
 *  - the live debugger, fed by ZenixLog on the main thread.
 *
 * The debugger is not decoration: every action here writes a gate line with the
 * ids and counts it actually saw, so a failed install on the user's device is
 * diagnosable from the console, from logcat (`adb logcat -s Zenix`), or from the
 * sidecar file at filesDir/zenix.log.
 */
class ZenixActivity : BaseActivity(), ZenixLog.Listener {

    private val viewBinding: ActivityZenixBinding by inflate()

    private val logAdapter = ZenixLogAdapter()
    private val spaceAdapter = ZenixSpaceAdapter(
        onInstallStack = { user -> confirmStackInstall(user.id) },
        onInstallApk = { user -> pickApk { uri -> installUri(uri, user.id) } },
        onDelete = { user -> confirmDelete(user.id) }
    )

    private var filterLevel = ZenixLog.LEVEL_VERBOSE
    private var pendingSpace = 0
    private var busy = false

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingPicker
        pendingPicker = null
        if (callback == null) {
            // Cannot happen unless two pickers raced; say so instead of guessing
            // which space the user meant.
            ZenixLog.w("INSTALL", "picker returned uri=$uri with no pending target")
            return@registerForActivityResult
        }
        callback(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.zenix_title, showBack = true)

        ZenixLog.setBuildStamp("${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}")
        ZenixLog.attachFile(File(filesDir, "zenix.log"))

        viewBinding.console.layoutManager = LinearLayoutManager(this)
        viewBinding.console.adapter = logAdapter
        viewBinding.spaceList.layoutManager = LinearLayoutManager(this)
        viewBinding.spaceList.adapter = spaceAdapter

        viewBinding.btnSelfTest.setOnClickListener { runSelfTest() }
        viewBinding.btnCreateSpace.setOnClickListener { promptCreateSpace() }
        viewBinding.btnInstallApk.setOnClickListener { userInstallApk(BlackBoxCore.getHostUserId()) }
        viewBinding.btnShareLog.setOnClickListener { shareLog() }
        viewBinding.btnCopyLog.setOnClickListener { copyLog() }
        viewBinding.btnClearLog.setOnClickListener {
            ZenixLog.clear()
            logAdapter.seed(ZenixLog.snapshot())
        }
        viewBinding.btnFilter.setOnClickListener { cycleFilter() }

        logAdapter.seed(ZenixLog.snapshot())
        ZenixLog.register(this)

        ZenixLog.i("ZENIX", "console opened, host=" + BlackBoxCore.getHostPkg()
                + " abi=" + (if (BlackBoxCore.is64Bit()) "64" else "32")
                + " lines=" + ZenixLog.lineCount())
        refreshStatus()
        refreshSpaces()
    }

    override fun onDestroy() {
        ZenixLog.unregister(this)
        super.onDestroy()
    }

    override fun onLog(entry: ZenixLog.Entry) {
        logAdapter.append(entry)
        viewBinding.console.scrollToPosition(logAdapter.itemCount - 1)
        updateConsoleTitle()
    }

    // ------------------------------------------------------------- status

    private fun refreshStatus() {
        val context = this
        val abiLine = if (BlackBoxCore.is64Bit()) "arm64-v8a only" else "armeabi-v7a only"
        val payloadMissing = ZenixPayload.missingFiles(context)

        viewBinding.statusSummary.text = getString(
            R.string.zenix_status_line,
            BlackBoxCore.getHostPkg(),
            abiLine
        )
        viewBinding.statusDetail.text = buildString {
            append("payload: ")
            append(if (payloadMissing.isEmpty()) "staged (${ZenixPayload.FILES.size})" else "MISSING $payloadMissing")
            append('\n')
            append("payload dir: ").append(ZenixPayload.payloadDir(context).absolutePath)
            append('\n')
            append("spaces: ").append(BlackBoxCore.get().users.size)
            append("   log file: ").append(ZenixLog.getFile()?.absolutePath ?: "off")
        }
        updateConsoleTitle()
    }

    private fun updateConsoleTitle() {
        viewBinding.consoleTitle.text = getString(
            R.string.zenix_console_count,
            ZenixLog.lineCount(),
            ZenixLog.count(ZenixLog.LEVEL_ERROR),
            ZenixLog.count(ZenixLog.LEVEL_WARN)
        )
    }

    private fun refreshSpaces() {
        val users = BlackBoxCore.get().users
        if (users == null || users.isEmpty()) {
            ZenixLog.w("SPACE", "container reports zero spaces")
        }
        spaceAdapter.submit(users ?: emptyList())
        users?.forEach { user ->
            spaceAdapter.setStatus(ZenixSpaceManager.status(user.id))
        }
        refreshStatus()
    }

    // ------------------------------------------------------------ actions

    private fun runSelfTest() {
        val report = try {
            ZenixSpaceManager.selfTest(this)
        } catch (tr: Throwable) {
            ZenixLog.fault("TEST", "self test threw", tr)
            listOf("self test aborted: ${tr.javaClass.simpleName}")
        }
        MaterialDialog(this).show {
            title(res = R.string.zenix_self_test)
            message(text = report.joinToString("\n"))
            positiveButton(res = android.R.string.ok)
        }
    }

    private fun promptCreateSpace() {
        MaterialDialog(this).show {
            title(res = R.string.zenix_new_space)
            input(hintRes = R.string.zenix_space_name) { _, input ->
                val name = input.toString().trim()
                val id = ZenixSpaceManager.createSpace(name)
                if (id < 0) {
                    toast(R.string.zenix_space_failed)
                    return@input
                }
                refreshSpaces()
                if (ZenixPayload.missingFiles(this@ZenixActivity).isEmpty()) {
                    confirmStackInstall(id)
                } else {
                    ZenixLog.w("SPACE", "space $id created; microG payload not staged, auto-install skipped")
                    toast(R.string.zenix_payload_missing)
                }
            }
            positiveButton(res = R.string.zenix_create)
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun confirmStackInstall(userId: Int) {
        if (busy) {
            ZenixLog.w("MICROG", "install already running, space=$userId ignored")
            return
        }
        val installed = ZenixSpaceManager.status(userId).complete()
        val note = if (installed) R.string.zenix_microg_reinstall_hint else R.string.zenix_microg_install_hint
        MaterialDialog(this).show {
            title(text = "microG -> space $userId")
            message(res = note)
            positiveButton(res = R.string.zenix_install) {
                runStackInstall(userId)
            }
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun runStackInstall(userId: Int) {
        if (busy) return
        busy = true
        viewBinding.btnSelfTest.isEnabled = false
        ZenixLog.i("MICROG", "queued: space=$userId")
        // Worker thread: installs copy up to ~100 MB and must never touch the
        // main thread. ZenixLog is already thread safe and coalesces UI posts.
        Thread({
            try {
                val ok = ZenixSpaceManager.installMicroG(applicationContext, userId)
                runOnUiThread {
                    busy = false
                    viewBinding.btnSelfTest.isEnabled = true
                    spaceAdapter.setStatus(ZenixSpaceManager.status(userId))
                    toast(if (ok) R.string.zenix_microg_ok else R.string.zenix_microg_fail)
                    refreshStatus()
                }
            } catch (tr: Throwable) {
                ZenixLog.fault("MICROG", "install worker threw", tr)
                runOnUiThread {
                    busy = false
                    viewBinding.btnSelfTest.isEnabled = true
                    toast(R.string.zenix_microg_fail)
                }
            }
        }, "zenix-microg").start()
    }

    private fun userInstallApk(userId: Int) {
        pickApk { uri -> installUri(uri, userId) }
    }

    private fun pickApk(onPicked: (Uri?) -> Unit) {
        // Reuses the same contract the rest of the app uses for APKs.
        pendingPicker = onPicked
        try {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        } catch (tr: Throwable) {
            ZenixLog.fault("INSTALL", "cannot open file picker", tr)
            toast(R.string.zenix_install_pick_failed)
        }
    }

    private var pendingPicker: ((Uri?) -> Unit)? = null

    private fun installUri(uri: Uri?, userId: Int) {
        if (uri == null) {
            ZenixLog.w("INSTALL", "file picker cancelled, space=$userId")
            return
        }
        pendingSpace = userId
        ZenixLog.i("INSTALL", "picked uri=$uri space=$userId")
        Thread({
            val result = ZenixSpaceManager.installFromUri(uri, userId)
            runOnUiThread {
                if (result != null && result.success) {
                    toast(getString(R.string.zenix_install_ok, result.packageName ?: "?"))
                } else {
                    toast(getString(R.string.zenix_install_fail, result?.msg ?: "null result"))
                }
                spaceAdapter.setStatus(ZenixSpaceManager.status(userId))
                refreshSpaces()
            }
        }, "zenix-install").start()
    }

    private fun confirmDelete(userId: Int) {
        if (userId <= 0) {
            toast(R.string.zenix_space_zero_protected)
            return
        }
        MaterialDialog(this).show {
            title(text = "Delete space $userId")
            message(text = "All apps and microG data inside space $userId are destroyed. Space 0 is never touched.")
            positiveButton(res = R.string.zenix_delete_space) {
                val ok = ZenixSpaceManager.deleteSpace(userId)
                toast(if (ok) R.string.zenix_space_deleted else R.string.zenix_space_delete_failed)
                refreshSpaces()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun cycleFilter() {
        filterLevel = when (filterLevel) {
            ZenixLog.LEVEL_VERBOSE -> ZenixLog.LEVEL_INFO
            ZenixLog.LEVEL_INFO -> ZenixLog.LEVEL_WARN
            ZenixLog.LEVEL_WARN -> ZenixLog.LEVEL_ERROR
            else -> ZenixLog.LEVEL_VERBOSE
        }
        logAdapter.setMinLevel(filterLevel)
        viewBinding.btnFilter.text = when (filterLevel) {
            ZenixLog.LEVEL_VERBOSE -> getString(R.string.zenix_filter_all)
            ZenixLog.LEVEL_INFO -> getString(R.string.zenix_filter_info)
            ZenixLog.LEVEL_WARN -> getString(R.string.zenix_filter_warn)
            else -> getString(R.string.zenix_filter_error)
        }
    }

    private fun copyLog() {
        val text = ZenixLog.dump()
        if (text.isBlank()) {
            toast(R.string.zenix_log_empty)
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Zenix log", text))
        toast(getString(R.string.zenix_log_copied, ZenixLog.lineCount()))
    }

    private fun shareLog() {
        val file = ZenixLog.getFile()
        if (file == null || !file.isFile) {
            copyLog()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.zenix.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Zenix session log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.zenix_share_log)))
        } catch (tr: Throwable) {
            ZenixLog.fault("CONSOLE", "share failed, falling back to clipboard", tr)
            copyLog()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        @JvmStatic
        fun start(context: Context) {
            context.startActivity(Intent(context, ZenixActivity::class.java))
        }
    }
}
