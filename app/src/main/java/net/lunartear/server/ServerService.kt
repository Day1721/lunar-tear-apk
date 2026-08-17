package net.lunartear.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * Foreground service owning the Go servers.
 *
 * Runs in its own `:server` process (see AndroidManifest). That isolation is the
 * point: internal/masterdata reaches 24 log.Fatal sites while parsing
 * user-supplied master data, and log.Fatal is os.Exit — so a bad asset file kills
 * this process and leaves the UI alive to report it.
 */
class ServerService : Service() {

    private lateinit var paths: AppPaths
    private lateinit var prefs: Prefs
    private lateinit var authProcess: AuthProcess

    /** Mobile.start blocks for seconds decrypting and parsing master data. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lunar-tear-ctl") }

    private val callbacks = RemoteCallbackList<IServerCallback>()

    @Volatile
    private var state: ServerState = ServerState.STOPPED

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var activeConfig: ServerConfig? = null

    // --- log batching ---------------------------------------------------------
    // Upstream logging is extremely chatty (internal/service/octo.go:188-192 dumps
    // every request header of every non-asset request). One Binder transaction per
    // line would swamp the UI process, so lines are coalesced.
    private val pendingLines = ArrayList<String>(256)
    private lateinit var logThread: HandlerThread
    private lateinit var logHandler: Handler
    private var flushScheduled = false

    override fun onCreate() {
        super.onCreate()
        paths = AppPaths(this)
        prefs = Prefs(this)
        authProcess = AuthProcess(this, paths)

        logThread = HandlerThread("lunar-tear-log").apply { start() }
        logHandler = Handler(logThread.looper)

        // Standard gomobile initialisation; must happen before any Mobile call.
        go.Seq.setContext(applicationContext)

        mobile.Mobile.setLogger(object : mobile.Logger {
            override fun log(line: String) = enqueueLine(line)
        })

        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_PAYLOAD)
                val payload = raw?.let { runCatching { StartPayload.fromJson(it) }.getOrNull() }
                if (payload == null) {
                    Log.e(TAG, "ACTION_START without a usable payload")
                } else {
                    prefs.shouldRun = true
                    // Remember it so a sticky restart can come back up without
                    // reading the UI process's SharedPreferences.
                    runCatching { StartPayload.cacheFile(this).writeText(raw) }
                    doStart(payload)
                }
            }

            ACTION_STOP -> {
                prefs.shouldRun = false
                doStop(thenStopSelf = true)
            }

            // Null intent means Android recreated us after killing the process.
            // Come back automatically if the user had us running.
            null -> if (prefs.shouldRun && state == ServerState.STOPPED) {
                val cached = runCatching { StartPayload.cacheFile(this).readText() }.getOrNull()
                val payload = cached?.let { runCatching { StartPayload.fromJson(it) }.getOrNull() }
                if (payload == null) {
                    Log.w(TAG, "sticky restart with no cached payload; staying down")
                    prefs.shouldRun = false
                } else {
                    enqueueLine("[service] restarted by the system; bringing the server back up")
                    doStart(payload)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        doStop(thenStopSelf = false)
        callbacks.kill()
        worker.shutdownNow()
        logThread.quitSafely()
        super.onDestroy()
    }

    // --- lifecycle ------------------------------------------------------------

    private fun doStart(payload: StartPayload) {
        if (state == ServerState.RUNNING || state == ServerState.STARTING) return

        val cfg = payload.config
        activeConfig = cfg
        setState(ServerState.STARTING, "Starting…")
        // Must be foreground before any slow work, or Android will complain.
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), foregroundType())

        worker.execute {
            try {
                val assets = AssetValidator.validate(paths.dataRoot)
                if (!assets.ok) {
                    throw IllegalStateException(assets.problems.joinToString("\n"))
                }
                assets.problems.forEach { enqueueLine("[assets] warning: $it") }
                enqueueLine("[assets] revisions present: ${assets.presentTrees.joinToString(", ")}")

                if (!paths.ensureDbDir()) {
                    throw IllegalStateException("Could not create ${paths.dbDir}")
                }

                // Auth first: the game server is handed its URL up front and
                // resolveAuthToken (internal/service/user.go:261) would fail until
                // it answers.
                if (cfg.authEnabled) {
                    authProcess.start(
                        port = cfg.authPort,
                        secretHex = payload.authSecretHex,
                        onLog = ::enqueueLine,
                        onExit = { code ->
                            if (state == ServerState.RUNNING) {
                                enqueueLine("[auth] auth server died (code $code); login and account transfer are unavailable")
                                updateNotification()
                            }
                        },
                    )
                }

                mobile.Mobile.start(cfg.toGoConfigJson(paths, payload.adminToken))

                setState(ServerState.RUNNING, cfg.endpointSummary())
                updateNotification()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                runCatching { authProcess.stop() }
                runCatching { mobile.Mobile.stop() }
                // Do not let a sticky restart loop on a permanent failure.
                prefs.shouldRun = false
                lastError = t.message ?: t.toString()
                setState(ServerState.ERROR, lastError)
                enqueueLine("[service] start failed: $lastError")
                updateNotification()
            }
        }
    }

    private fun doStop(thenStopSelf: Boolean) {
        if (state == ServerState.STOPPED && !authProcess.isAlive) {
            if (thenStopSelf) stopSelfCleanly()
            return
        }
        setState(ServerState.STOPPING, "Stopping…")
        updateNotification()

        worker.execute {
            runCatching { mobile.Mobile.stop() }
                .onFailure { Log.w(TAG, "Mobile.stop", it) }
            runCatching { authProcess.stop() }
                .onFailure { Log.w(TAG, "authProcess.stop", it) }

            lastError = null
            setState(ServerState.STOPPED, "Stopped")
            if (thenStopSelf) stopSelfCleanly()
        }
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setState(s: ServerState, detail: String?) {
        state = s
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onState(s.ordinal, detail) }
        }
        callbacks.finishBroadcast()
    }

    // --- logs -----------------------------------------------------------------

    private fun enqueueLine(line: String) {
        synchronized(pendingLines) {
            // Bound the queue: if the UI is not draining, drop the oldest rather
            // than grow without limit. The full history stays in the Go ring buffer.
            if (pendingLines.size >= MAX_PENDING) pendingLines.removeAt(0)
            pendingLines.add(line)
            if (flushScheduled) return
            flushScheduled = true
        }
        logHandler.postDelayed(::flushLines, FLUSH_MS)
    }

    private fun flushLines() {
        val batch: Array<String>
        synchronized(pendingLines) {
            flushScheduled = false
            if (pendingLines.isEmpty()) return
            batch = pendingLines.toTypedArray()
            pendingLines.clear()
        }
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onLogLines(batch) }
        }
        callbacks.finishBroadcast()
    }

    // --- notification ---------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            // LOW: ongoing status, must never buzz.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cfg = activeConfig
        val title = when (state) {
            ServerState.RUNNING -> getString(R.string.notif_running)
            ServerState.STARTING -> getString(R.string.notif_starting)
            ServerState.STOPPING -> getString(R.string.notif_stopping)
            ServerState.ERROR -> getString(R.string.notif_error)
            ServerState.STOPPED -> getString(R.string.notif_stopped)
        }
        val text = when {
            state == ServerState.ERROR -> lastError ?: getString(R.string.notif_error)
            cfg != null -> cfg.endpointSummary() +
                if (cfg.authEnabled && !authProcess.isAlive && state == ServerState.RUNNING) {
                    "  ·  auth down"
                } else ""
            else -> ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    // --- AIDL -----------------------------------------------------------------

    // Note: the Stub declares getState()/getLastError(), so an unqualified `state`
    // or `lastError` in here resolves to the stub's own accessor, not the
    // service's field. Everything referring to service state is qualified.
    private val binder = object : IServerService.Stub() {
        override fun getState(): Int = this@ServerService.state.ordinal

        override fun getLastError(): String? = this@ServerService.lastError

        override fun getLogSnapshot(): String = mobile.Mobile.logSnapshot()

        override fun registerCallback(cb: IServerCallback?) {
            cb ?: return
            callbacks.register(cb)
            // Give the freshly bound UI the current state immediately.
            runCatching {
                cb.onState(
                    this@ServerService.state.ordinal,
                    this@ServerService.lastError ?: activeConfig?.endpointSummary(),
                )
            }
        }

        override fun unregisterCallback(cb: IServerCallback?) {
            cb?.let { callbacks.unregister(it) }
        }

        override fun reclaimAccount(name: String?): String =
            mobile.Mobile.reclaimAccount(paths.gameDb.absolutePath, name ?: "")

        override fun listAccounts(): String =
            mobile.Mobile.listAccounts(paths.gameDb.absolutePath)
    }

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "lunar_tear_server"
        private const val NOTIF_ID = 1
        private const val FLUSH_MS = 250L
        private const val MAX_PENDING = 2000

        const val ACTION_START = "net.lunartear.server.START"
        const val ACTION_STOP = "net.lunartear.server.STOP"
        const val EXTRA_PAYLOAD = "start_payload"

        fun startIntent(context: android.content.Context, payloadJson: String): Intent =
            Intent(context, ServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PAYLOAD, payloadJson)

        fun stopIntent(context: android.content.Context): Intent =
            Intent(context, ServerService::class.java).setAction(ACTION_STOP)
    }
}
