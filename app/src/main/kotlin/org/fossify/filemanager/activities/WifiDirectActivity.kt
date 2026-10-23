package org.fossify.filemanager.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Sends or receives files over Wi-Fi Direct (Wi-Fi P2P).
 *
 * - Started with [EXTRA_PATHS]  -> "send" mode: shows nearby devices, tap one to connect and send.
 * - Started without extras      -> "receive" mode: makes this phone discoverable and waits.
 *
 * After the P2P group is formed, the group owner opens a ServerSocket and the other side connects
 * to it. Which side is the group owner is decided by the system, so the TCP role (server/client)
 * is independent from the transfer role (sender/receiver).
 */
@SuppressLint("MissingPermission")
class WifiDirectActivity : SimpleActivity() {

    companion object {
        const val EXTRA_PATHS = "wifi_direct_paths"
        private const val PORT = 8988
        private const val MAGIC = 0x46444D31 // "FDM1"
        private const val BUFFER_SIZE = 64 * 1024
        private const val RECEIVE_DIR = "WifiDirect"
    }

    private class SendEntry(val file: File, val relativePath: String)

    private val sendEntries = ArrayList<SendEntry>()
    private var sendMode = false

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val peers = ArrayList<WifiP2pDevice>()
    private lateinit var peersAdapter: ArrayAdapter<String>

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var peersList: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var closeButton: Button

    @Volatile
    private var transferStarted = false

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var lastProgressUpdate = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startP2p()
            } else {
                setStatus(getString(R.string.wifi_direct_permission_denied))
            }
        }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val manager = p2p ?: return
            val ch = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        setStatus(getString(R.string.wifi_direct_turn_on_wifi))
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(ch) { list -> updatePeers(list.deviceList.toList()) }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(ch) { info ->
                        if (info != null && info.groupFormed) {
                            onGroupFormed(info)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
        sendMode = paths.isNotEmpty()
        if (sendMode) {
            collectEntries(paths)
        }

        buildUi()

        if (sendMode && sendEntries.isEmpty()) {
            setStatus(getString(R.string.wifi_direct_no_files))
            return
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            setStatus(getString(R.string.wifi_direct_unsupported))
            return
        }

        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startP2p()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
        runCatching { serverSocket?.close() }
        val manager = p2p
        val ch = channel
        if (manager != null && ch != null) {
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.removeGroup(ch, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { ch.close() }
            }
        }
    }

    // ---------------------------------------------------------------- setup

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun collectEntries(paths: List<String>) {
        for (path in paths) {
            val file = File(path)
            when {
                file.isDirectory -> {
                    file.walkTopDown().filter { it.isFile }.forEach {
                        val rel = file.name + "/" + it.relativeTo(file).path.replace(File.separatorChar, '/')
                        sendEntries.add(SendEntry(it, rel))
                    }
                }

                file.isFile -> sendEntries.add(SendEntry(file, file.name))
            }
        }
    }

    private fun startP2p() {
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            setStatus(getString(R.string.wifi_direct_unsupported))
            return
        }
        p2p = manager
        channel = manager.initialize(this, mainLooper, null)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        setStatus(getString(if (sendMode) R.string.wifi_direct_searching else R.string.wifi_direct_waiting))
        discoverPeers()
    }

    private fun discoverPeers() {
        val manager = p2p ?: return
        val ch = channel ?: return
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}

            override fun onFailure(reason: Int) {
                val text = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> getString(R.string.wifi_direct_unsupported)
                    WifiP2pManager.BUSY -> getString(R.string.wifi_direct_busy)
                    else -> getString(R.string.wifi_direct_turn_on_wifi)
                }
                setStatus(text)
            }
        })
    }

    // ---------------------------------------------------------------- peers / connect

    private fun updatePeers(list: List<WifiP2pDevice>) {
        if (!sendMode || transferStarted) return
        peers.clear()
        peers.addAll(list)
        peersAdapter.clear()
        peersAdapter.addAll(list.map { "${it.deviceName.ifEmpty { it.deviceAddress }}  (${statusName(it.status)})" })
        peersAdapter.notifyDataSetChanged()
        if (list.isNotEmpty()) {
            setStatus(getString(R.string.wifi_direct_pick_device))
        }
    }

    private fun statusName(status: Int) = when (status) {
        WifiP2pDevice.AVAILABLE -> getString(R.string.wifi_direct_status_available)
        WifiP2pDevice.INVITED -> getString(R.string.wifi_direct_status_invited)
        WifiP2pDevice.CONNECTED -> getString(R.string.wifi_direct_status_connected)
        else -> getString(R.string.wifi_direct_status_unavailable)
    }

    private fun connectTo(device: WifiP2pDevice) {
        val manager = p2p ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        setStatus(getString(R.string.wifi_direct_connecting, device.deviceName))
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}

            override fun onFailure(reason: Int) {
                setStatus(getString(R.string.wifi_direct_connect_failed))
            }
        })
    }

    // ---------------------------------------------------------------- transfer

    private fun onGroupFormed(info: WifiP2pInfo) {
        if (transferStarted) return
        transferStarted = true
        runOnUiThreadSafe {
            peersList.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE
            setStatus(getString(R.string.wifi_direct_connected))
        }

        Thread {
            try {
                openSocket(info).use { socket ->
                    if (sendMode) sendAll(socket) else receiveAll(socket)
                }
            } catch (e: Exception) {
                runOnUiThreadSafe {
                    setStatus(getString(R.string.wifi_direct_error, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                transferStarted = false
                runOnUiThreadSafe { leaveGroup() }
            }
        }.start()
    }

    private fun openSocket(info: WifiP2pInfo): Socket {
        if (info.isGroupOwner) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
            server.soTimeout = 60_000
            serverSocket = server
            try {
                return server.accept()
            } finally {
                runCatching { server.close() }
                serverSocket = null
            }
        }

        val host = info.groupOwnerAddress
        var last: IOException? = null
        // The group owner may need a moment to start listening.
        repeat(15) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, PORT), 5_000)
                return socket
            } catch (e: IOException) {
                last = e
                Thread.sleep(1_000)
            }
        }
        throw last ?: IOException("Cannot connect")
    }

    private fun sendAll(socket: Socket) {
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
        val total = sendEntries.sumOf { it.file.length() }
        out.writeInt(MAGIC)
        out.writeInt(sendEntries.size)
        out.writeLong(total)

        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        for ((index, entry) in sendEntries.withIndex()) {
            val size = entry.file.length()
            out.writeUTF(entry.relativePath)
            out.writeLong(size)
            var sent = 0L
            FileInputStream(entry.file).use { input ->
                while (sent < size) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                    if (n < 0) throw IOException("File changed: ${entry.file.name}")
                    out.write(buffer, 0, n)
                    sent += n
                    done += n
                    reportProgress(done, total, entry.file.name, index + 1, sendEntries.size)
                }
            }
        }
        out.flush()
        socket.shutdownOutput()

        // Wait until the receiver confirms that everything has been written to disk.
        socket.soTimeout = 30_000
        val ack = socket.getInputStream().read()
        if (ack != 1) throw IOException("No confirmation from receiver")

        runOnUiThreadSafe {
            progressBar.progress = progressBar.max
            setStatus(getString(R.string.wifi_direct_done_send, sendEntries.size))
        }
    }

    private fun receiveAll(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
        if (input.readInt() != MAGIC) throw IOException("Unknown protocol")
        val count = input.readInt()
        val total = input.readLong()
        if (count < 0 || count > 100_000 || total < 0) throw IOException("Bad header")

        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), RECEIVE_DIR)
        root.mkdirs()

        val buffer = ByteArray(BUFFER_SIZE)
        val received = ArrayList<String>()
        var done = 0L
        for (index in 0 until count) {
            val relative = input.readUTF()
            val size = input.readLong()
            if (size < 0) throw IOException("Bad file size")
            val dest = uniqueFile(safeDestination(root, relative))
            dest.parentFile?.mkdirs()

            var got = 0L
            FileOutputStream(dest).use { output ->
                while (got < size) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - got).toInt())
                    if (n < 0) throw IOException("Connection closed early")
                    output.write(buffer, 0, n)
                    got += n
                    done += n
                    reportProgress(done, total, dest.name, index + 1, count)
                }
            }
            received.add(dest.absolutePath)
        }

        socket.getOutputStream().apply {
            write(1)
            flush()
        }

        MediaScannerConnection.scanFile(applicationContext, received.toTypedArray(), null, null)
        runOnUiThreadSafe {
            progressBar.progress = progressBar.max
            setStatus(getString(R.string.wifi_direct_done_receive, count, "${Environment.DIRECTORY_DOWNLOADS}/$RECEIVE_DIR"))
        }
    }

    /** Rejects absolute paths and ".." so a malicious sender cannot write outside the target folder. */
    private fun safeDestination(root: File, relative: String): File {
        val parts = relative.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) throw IOException("Unsafe path")
        val dest = File(root, parts.joinToString(File.separator))
        val rootPath = root.canonicalPath + File.separator
        if (!dest.canonicalPath.startsWith(rootPath)) throw IOException("Unsafe path")
        return dest
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        var candidate = File(file.parentFile, "$name ($i)$ext")
        while (candidate.exists()) {
            i++
            candidate = File(file.parentFile, "$name ($i)$ext")
        }
        return candidate
    }

    private fun reportProgress(done: Long, total: Long, name: String, index: Int, count: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressUpdate < 100 && done < total) return
        lastProgressUpdate = now
        val percent = if (total > 0) ((done * 1000) / total).toInt() else 0
        runOnUiThreadSafe {
            progressBar.progress = percent
            val label = getString(
                if (sendMode) R.string.wifi_direct_sending else R.string.wifi_direct_receiving,
                index, count, name
            )
            progressText.text = "$label\n${android.text.format.Formatter.formatFileSize(this, done)} / " +
                android.text.format.Formatter.formatFileSize(this, total)
        }
    }

    private fun leaveGroup() {
        val manager = p2p ?: return
        val ch = channel ?: return
        runCatching { manager.removeGroup(ch, null) }
        runCatching { manager.stopPeerDiscovery(ch, null) }
        closeButton.visibility = View.VISIBLE
    }

    private fun runOnUiThreadSafe(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread(block)
    }

    private fun setStatus(text: String) {
        if (::statusView.isInitialized) statusView.text = text
    }

    // ---------------------------------------------------------------- UI (built in code, styled like the rest of the app)

    private fun buildUi() {
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
                peers.getOrNull(position)?.let { connectTo(it) }
            }
        }
        root.addView(peersList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressText = TextView(this).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        root.addView(progressText)

        // Spacer so the button stays at the bottom when the list is hidden.
        root.addView(View(this), LinearLayout.LayoutParams(0, 0, if (sendMode) 0f else 1f))

        closeButton = Button(this).apply {
            text = getString(R.string.wifi_direct_close)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        root.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }
}
