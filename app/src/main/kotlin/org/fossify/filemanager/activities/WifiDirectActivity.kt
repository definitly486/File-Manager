package org.fossify.filemanager.activities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.filemanager.R
import org.fossify.filemanager.helpers.TcTheme
import org.fossify.filemanager.services.WifiDirectService

/**
 * Screen for Wi-Fi Direct sharing. All the work is done by [WifiDirectService]; this activity only
 * shows its state, so the transfer keeps running when the screen is closed.
 *
 * - Started with [EXTRA_PATHS]   -> send mode: starts the service and lists nearby devices.
 * - Started without extras       -> receive mode: starts the service and waits for a sender.
 * - Started with [EXTRA_ATTACH]  -> (notification tap) only attaches to the running service.
 */
class WifiDirectActivity : SimpleActivity(), WifiDirectService.Listener {

    companion object {
        const val EXTRA_PATHS = "wifi_direct_paths"
        const val EXTRA_ATTACH = "wifi_direct_attach"
    }

    private var service: WifiDirectService? = null
    private var bound = false
    private var startPaths = ArrayList<String>()

    private val peers = ArrayList<WifiP2pDevice>()
    private lateinit var peersAdapter: ArrayAdapter<String>

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var peersList: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var cancelButton: Button
    private lateinit var closeButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as WifiDirectService.LocalBinder).getService()
            service = s
            s.attach(this@WifiDirectActivity)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Notification permission is optional: the service still runs without a visible notification.
            if (hasRequiredPermissions()) {
                beginSession()
            } else {
                statusView.text = getString(R.string.wifi_direct_permission_denied)
                cancelButton.visibility = View.GONE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startPaths = ArrayList(intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty())
        buildUi(startPaths.isNotEmpty())

        // Re-created by the system (or opened from the notification): never start a second session.
        val attachOnly = intent.getBooleanExtra(EXTRA_ATTACH, false) || savedInstanceState != null
        if (attachOnly) {
            bound = bindService(Intent(this, WifiDirectService::class.java), connection, 0)
            if (!bound) finish()
            return
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            statusView.text = getString(R.string.wifi_direct_unsupported)
            cancelButton.visibility = View.GONE
            return
        }

        if (hasRequiredPermissions()) {
            beginSession()
        } else {
            permissionLauncher.launch(permissionsToRequest())
        }
    }

    override fun onDestroy() {
        service?.let {
            it.detach(this)
            // Closing the device list of a sender makes no sense in the background, so drop it.
            // A receiver that is waiting or any running transfer keeps going (see the notification).
            if (isFinishing) it.cancelIfBrowsing()
        }
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session

    private fun beginSession() {
        WifiDirectService.start(this, startPaths)
        bound = bindService(Intent(this, WifiDirectService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRequiredPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionsToRequest(): Array<String> {
        val list = ArrayList(requiredPermissions().toList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    // ---------------------------------------------------------------- service callbacks

    override fun onStateChanged(state: WifiDirectService.State) {
        val phase = state.phase

        titleView.text = getString(if (state.sendMode) R.string.wifi_direct_title_send else R.string.wifi_direct_title_receive)
        statusView.text = state.status

        val showPeers = state.sendMode && phase == WifiDirectService.Phase.SEARCHING
        peersList.visibility = if (showPeers) View.VISIBLE else View.GONE
        if (showPeers) updatePeers(state.peers)

        val showProgress = phase == WifiDirectService.Phase.TRANSFERRING ||
            (phase == WifiDirectService.Phase.FINISHED && state.progress > 0)
        progressBar.visibility = if (showProgress) View.VISIBLE else View.GONE
        progressText.visibility = if (showProgress && state.progressText.isNotEmpty()) View.VISIBLE else View.GONE
        progressBar.progress = state.progress
        progressText.text = state.progressText

        val active = phase == WifiDirectService.Phase.SEARCHING ||
            phase == WifiDirectService.Phase.CONNECTING ||
            phase == WifiDirectService.Phase.TRANSFERRING
        cancelButton.visibility = if (active) View.VISIBLE else View.GONE
        closeButton.text = getString(if (active) R.string.wifi_direct_hide else R.string.wifi_direct_close)
    }

    private fun updatePeers(list: List<WifiP2pDevice>) {
        peers.clear()
        peers.addAll(list)
        peersAdapter.clear()
        peersAdapter.addAll(list.map { "${it.deviceName.ifEmpty { it.deviceAddress }}  (${statusName(it.status)})" })
        peersAdapter.notifyDataSetChanged()
    }

    private fun statusName(status: Int) = when (status) {
        WifiP2pDevice.AVAILABLE -> getString(R.string.wifi_direct_status_available)
        WifiP2pDevice.INVITED -> getString(R.string.wifi_direct_status_invited)
        WifiP2pDevice.CONNECTED -> getString(R.string.wifi_direct_status_connected)
        else -> getString(R.string.wifi_direct_status_unavailable)
    }

    // ---------------------------------------------------------------- UI (built in code, styled like the rest of the app)

    private fun buildUi(sendMode: Boolean) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val textColor = TcTheme.text(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TcTheme.background(this@WifiDirectActivity))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16), bars.top + dp(16), dp(16), bars.bottom + dp(16))
            insets
        }

        titleView = TextView(this).apply {
            text = getString(if (sendMode) R.string.wifi_direct_title_send else R.string.wifi_direct_title_receive)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleView)

        statusView = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusView)

        peersAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also {
                    (it as TextView).setTextColor(textColor)
                }
            }
        }
        peersList = ListView(this).apply {
            adapter = peersAdapter
            visibility = if (sendMode) View.VISIBLE else View.GONE
            setOnItemClickListener { _, _, position, _ ->
                peers.getOrNull(position)?.let { service?.connectTo(it) }
            }
        }
        // The list takes the free space in send mode; in receive mode a spacer pushes the buttons down.
        root.addView(peersList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        progressText = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        root.addView(progressText)

        root.addView(View(this), LinearLayout.LayoutParams(0, 0, if (sendMode) 0f else 1f))

        cancelButton = Button(this).apply {
            text = getString(R.string.wifi_direct_cancel)
            visibility = View.GONE
            setOnClickListener {
                service?.cancel()
            }
        }
        root.addView(
            cancelButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        closeButton = Button(this).apply {
            text = getString(R.string.wifi_direct_close)
            setOnClickListener { finish() }
        }
        root.addView(
            closeButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)
    }
}
