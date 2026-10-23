package org.fossify.filemanager.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.MainActivity
import org.fossify.filemanager.activities.WifiDirectActivity
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
 * Foreground service that sends or receives files over Wi-Fi Direct (Wi-Fi P2P), so a transfer
 * survives the user leaving the screen.
 *
 * - ACTION_START with EXTRA_PATHS  -> "send" mode: lists nearby devices, [connectTo] starts the transfer.
 * - ACTION_START without extras    -> "receive" mode: makes the phone discoverable and waits.
 * - ACTION_CANCEL                  -> aborts everything (the notification's Cancel button).
 *
 * After the P2P group is formed the group owner opens a ServerSocket and the other side connects to
 * it. The system decides who becomes group owner, so the TCP role (server/client) is independent of
 * the transfer role (sender/receiver).
 *
 * All state is changed on the main thread; the transfer thread only posts to [mainHandler].
 */
@SuppressLint("MissingPermission")
class WifiDirectService : Service() {

    enum class Phase { IDLE, SEARCHING, CONNECTING, TRANSFERRING, FINISHED }

    class State(
        val sendMode: Boolean,
        val phase: Phase,
        val status: String,
        val peers: List<WifiP2pDevice>,
        /** 0..1000 */
        val progress: Int,
        val progressText: String
    )

    interface Listener {
        /** Always called on the main thread. */
        fun onStateChanged(state: State)
    }

    inner class LocalBinder : Binder() {
        fun getService(): WifiDirectService = this@WifiDirectService
    }

    private class SendEntry(val file: File, val relativePath: String)

    companion object {
        const val ACTION_START = "org.fossify.filemanager.WIFI_DIRECT_START"
        const val ACTION_CANCEL = "org.fossify.filemanager.WIFI_DIRECT_CANCEL"
        const val EXTRA_PATHS = "wifi_direct_paths"

        private const val CHANNEL_ID = "wifi_direct_transfer"
        private const val NOTIFICATION_ONGOING = 4711
        private const val NOTIFICATION_RESULT = 4712

        private const val PORT = 8988
        private const val MAGIC = 0x46444D31 // "FDM1"
        private const val BUFFER_SIZE = 64 * 1024
        private const val RECEIVE_DIR = "WifiDirect"
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        /** Starts the service in the foreground. Pass an empty list to wait for incoming files. */
        fun start(context: Context, paths: ArrayList<String>) {
            val intent = Intent(context, WifiDirectService::class.java)
                .setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_PATHS, paths)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null

    // ---- session state (main thread only, except where marked @Volatile)
    private val sendEntries = ArrayList<SendEntry>()
    private var sendMode = false
    private var phase = Phase.IDLE
    private var status = ""
    private var peers: List<WifiP2pDevice> = emptyList()
    private var progress = 0
    private var progressLabel = ""
    private var progressText = ""

    @Volatile
    private var cancelled = false

    @Volatile
    private var transferRunning = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var resultMessage = ""

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null

    // touched by the transfer thread only
    private var lastProgressUpdate = 0L
    private var lastNotificationUpdate = 0L

    private val idleTimeout = Runnable {
        if (phase == Phase.SEARCHING) {
            finishSession(getString(R.string.wifi_direct_timeout))
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val manager = p2p ?: return
            val ch = channel ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED && !transferRunning) {
                        status = getString(R.string.wifi_direct_turn_on_wifi)
                        publish(false)
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(ch) { list ->
                        peers = list.deviceList.toList()
                        if (phase == Phase.SEARCHING && sendMode && peers.isNotEmpty()) {
                            status = getString(R.string.wifi_direct_pick_device)
                        }
                        publish(false)
                    }
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

    // ------------------------------------------------------------------ Service lifecycle

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForegroundService() requires this within a few seconds, so do it first.
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!transferRunning) {
                    beginSession(intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty())
                }
            }

            ACTION_CANCEL -> cancelSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        teardownP2p()
        releaseWakeLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ API for the activity

    fun attach(newListener: Listener) {
        listener = newListener
        newListener.onStateChanged(snapshot())
    }

    fun detach(oldListener: Listener) {
        if (listener === oldListener) listener = null
    }

    fun connectTo(device: WifiP2pDevice) {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (phase != Phase.SEARCHING || !sendMode) return

        phase = Phase.CONNECTING
        status = getString(R.string.wifi_direct_connecting, device.deviceName.ifEmpty { device.deviceAddress })
        publish()

        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}

            override fun onFailure(reason: Int) {
                if (phase == Phase.CONNECTING) {
                    phase = Phase.SEARCHING
                    status = getString(R.string.wifi_direct_connect_failed)
                    publish()
                }
            }
        })
    }

    fun cancel() = cancelSession()

    /** Used when the user closes the sender's device list: nothing is running yet, so drop the session. */
    fun cancelIfBrowsing() {
        if (sendMode && phase == Phase.SEARCHING) cancelSession()
    }

    // ------------------------------------------------------------------ session

    private fun beginSession(paths: List<String>) {
        teardownP2p()
        cancelled = false
        sendEntries.clear()
        peers = emptyList()
        progress = 0
        progressLabel = ""
        progressText = ""
        sendMode = paths.isNotEmpty()
        if (sendMode) collectEntries(paths)

        when {
            sendMode && sendEntries.isEmpty() -> finishSession(getString(R.string.wifi_direct_no_files))
            !packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ->
                finishSession(getString(R.string.wifi_direct_unsupported))

            else -> startP2p()
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
            finishSession(getString(R.string.wifi_direct_unsupported))
            return
        }
        val ch = manager.initialize(this, Looper.getMainLooper(), null)
        p2p = manager
        channel = ch

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        phase = Phase.SEARCHING
        status = getString(if (sendMode) R.string.wifi_direct_searching else R.string.wifi_direct_waiting)
        publish()

        // Do not wait forever in the background if nobody shows up.
        mainHandler.removeCallbacks(idleTimeout)
        mainHandler.postDelayed(idleTimeout, IDLE_TIMEOUT_MS)

        try {
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}

                override fun onFailure(reason: Int) {
                    status = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> getString(R.string.wifi_direct_unsupported)
                        WifiP2pManager.BUSY -> getString(R.string.wifi_direct_busy)
                        else -> getString(R.string.wifi_direct_turn_on_wifi)
                    }
                    publish()
                }
            })
        } catch (e: SecurityException) {
            finishSession(getString(R.string.wifi_direct_permission_denied))
        }
    }

    private fun teardownP2p() {
        mainHandler.removeCallbacks(idleTimeout)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
        val manager = p2p
        val ch = channel
        if (manager != null && ch != null) {
            runCatching { manager.stopPeerDiscovery(ch, null) }
            runCatching { manager.removeGroup(ch, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { ch.close() }
            }
        }
        p2p = null
        channel = null
    }

    private fun cancelSession() {
        if (phase == Phase.FINISHED) return
        cancelled = true
        runCatching { serverSocket?.close() }
        runCatching { activeSocket?.close() }
        // If a transfer is running its thread notices the closed socket and ends the session itself.
        if (!transferRunning) {
            finishSession(getString(R.string.wifi_direct_cancelled))
        }
    }

    private fun finishSession(message: String, success: Boolean = false) {
        mainHandler.removeCallbacks(idleTimeout)
        teardownP2p()
        phase = Phase.FINISHED
        status = message
        if (success) progress = 1000
        publish(false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (listener == null) {
            postResultNotification(message)
        }
        stopSelf()
    }

    // ------------------------------------------------------------------ transfer

    private fun onGroupFormed(info: WifiP2pInfo) {
        if (transferRunning || phase == Phase.FINISHED) return
        transferRunning = true
        mainHandler.removeCallbacks(idleTimeout)
        phase = Phase.TRANSFERRING
        status = getString(R.string.wifi_direct_connected)
        progress = 0
        publish()
        acquireWakeLock()

        Thread {
            var error: String? = null
            try {
                openSocket(info).use { socket ->
                    activeSocket = socket
                    if (sendMode) sendAll(socket) else receiveAll(socket)
                }
            } catch (e: Exception) {
                if (!cancelled) error = e.message ?: e.javaClass.simpleName
            } finally {
                activeSocket = null
            }

            mainHandler.post {
                transferRunning = false
                releaseWakeLock()
                when {
                    cancelled -> finishSession(getString(R.string.wifi_direct_cancelled))
                    error != null -> finishSession(getString(R.string.wifi_direct_error, error))
                    else -> finishSession(resultMessage, success = true)
                }
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
            if (cancelled) throw IOException("Cancelled")
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
        if (socket.getInputStream().read() != 1) throw IOException("No confirmation from receiver")

        resultMessage = getString(R.string.wifi_direct_done_send, sendEntries.size)
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
        resultMessage = getString(
            R.string.wifi_direct_done_receive, count, "${Environment.DIRECTORY_DOWNLOADS}/$RECEIVE_DIR"
        )
    }

    /** Rejects absolute paths and ".." so a malicious sender cannot write outside the target folder. */
    private fun safeDestination(root: File, relative: String): File {
        val parts = relative.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) throw IOException("Unsafe path")
        val dest = File(root, parts.joinToString(File.separator))
        if (!dest.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw IOException("Unsafe path")
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

    /** Called from the transfer thread. */
    private fun reportProgress(done: Long, total: Long, name: String, index: Int, count: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressUpdate < 100 && done < total) return
        lastProgressUpdate = now

        val permille = if (total > 0) ((done * 1000) / total).toInt() else 0
        val label = getString(
            if (sendMode) R.string.wifi_direct_sending else R.string.wifi_direct_receiving,
            index, count, name
        )
        val text = "$label\n${Formatter.formatFileSize(this, done)} / ${Formatter.formatFileSize(this, total)}"
        val updateNotification = now - lastNotificationUpdate >= 500
        if (updateNotification) lastNotificationUpdate = now

        mainHandler.post {
            if (phase != Phase.TRANSFERRING) return@post
            progress = permille
            progressLabel = label
            progressText = text
            publish(updateNotification)
        }
    }

    // ------------------------------------------------------------------ state / notifications

    private fun snapshot() = State(sendMode, phase, status, peers, progress, progressText)

    private fun publish(updateNotification: Boolean = true) {
        listener?.onStateChanged(snapshot())
        if (updateNotification && (phase == Phase.SEARCHING || phase == Phase.CONNECTING || phase == Phase.TRANSFERRING)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ONGOING, buildNotification())
        }
    }

    private fun enterForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ONGOING, notification)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.wifi_direct_channel_name), NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        // Reopens the screen and attaches it to this running session instead of starting a new one.
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WifiDirectActivity::class.java)
                .putExtra(WifiDirectActivity.EXTRA_ATTACH, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutable
        )
        val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, WifiDirectService::class.java).setAction(ACTION_CANCEL), immutable
        )

        val title = getString(if (sendMode) R.string.wifi_direct_title_send else R.string.wifi_direct_title_receive)
        val text = if (phase == Phase.TRANSFERRING && progressLabel.isNotEmpty()) progressLabel else status

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(
                if (phase == Phase.TRANSFERRING) 1000 else 0,
                if (phase == Phase.TRANSFERRING) progress else 0,
                phase != Phase.TRANSFERRING
            )
            .addAction(0, getString(R.string.wifi_direct_cancel), cancelIntent)
            .build()
    }

    private fun postResultNotification(message: String) {
        ensureChannel()
        val title = getString(if (sendMode) R.string.wifi_direct_title_send else R.string.wifi_direct_title_receive)
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share_vector)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_RESULT, notification)
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileManager:WifiDirect").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
