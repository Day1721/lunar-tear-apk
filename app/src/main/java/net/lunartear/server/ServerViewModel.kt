package net.lunartear.server

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Environment
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-process view of the server, which lives in the `:server` process. All
 * communication goes over the AIDL binder.
 */
class ServerViewModel(app: Application) : AndroidViewModel(app) {

    private val paths = AppPaths(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _config = MutableStateFlow(prefs.config)
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    private val _assets = MutableStateFlow<AssetValidator.Result?>(null)
    val assets: StateFlow<AssetValidator.Result?> = _assets.asStateFlow()

    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound.asStateFlow()

    /** Transient one-shot messages for snackbars. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val dataRootDisplay: String get() = AppPaths.displayRoot

    private var service: IServerService? = null

    private val callback = object : IServerCallback.Stub() {
        override fun onState(stateOrdinal: Int, detailText: String?) {
            _state.value = ServerState.fromOrdinal(stateOrdinal)
            _detail.value = detailText
        }

        override fun onLogLines(lines: Array<out String>?) {
            lines ?: return
            _logs.value = (_logs.value + lines).takeLast(MAX_LOG_LINES)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IServerService.Stub.asInterface(binder)
            service = svc
            _bound.value = true
            runCatching {
                svc.registerCallback(callback)
                _state.value = ServerState.fromOrdinal(svc.state)
                // Backfill history the UI missed while unbound.
                val snapshot = svc.logSnapshot
                if (!snapshot.isNullOrEmpty()) {
                    _logs.value = snapshot.lines().takeLast(MAX_LOG_LINES)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _bound.value = false
        }

        /**
         * The `:server` process died — almost certainly a log.Fatal inside
         * internal/masterdata, which is os.Exit and cannot be caught in Go. This
         * is exactly why the server runs in a separate process.
         */
        override fun onBindingDied(name: ComponentName?) {
            service = null
            _bound.value = false
            _state.value = ServerState.ERROR
            _detail.value = "The server process exited unexpectedly. This usually means the " +
                "master-data file is corrupt or incompatible. Check the log."
            rebind()
        }
    }

    init {
        bind()
        refreshAssets()
    }

    private fun bind() {
        val ctx = getApplication<Application>()
        ctx.bindService(
            Intent(ctx, ServerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun rebind() {
        val ctx = getApplication<Application>()
        runCatching { ctx.unbindService(connection) }
        bind()
    }

    override fun onCleared() {
        runCatching { service?.unregisterCallback(callback) }
        runCatching { getApplication<Application>().unbindService(connection) }
        super.onCleared()
    }

    // --- actions --------------------------------------------------------------

    fun updateConfig(cfg: ServerConfig) {
        _config.value = cfg
        prefs.config = cfg
    }

    fun refreshAssets() {
        viewModelScope.launch {
            _assets.value = withContext(Dispatchers.IO) {
                AssetValidator.validate(paths.dataRoot)
            }
        }
    }

    fun startServer() {
        val cfg = _config.value

        cfg.validate()?.let {
            _message.value = it
            return
        }
        if (!hasAllFilesAccess()) {
            _message.value = "Grant \"All files access\" first — the server reads the asset tree " +
                "directly from ${AppPaths.displayRoot}."
            return
        }
        val assets = _assets.value
        if (assets != null && !assets.ok) {
            _message.value = assets.problems.firstOrNull() ?: "Asset tree is incomplete."
            return
        }

        _logs.value = emptyList()
        // The UI process is the single source of truth: SharedPreferences caches
        // per-process, so the service must be handed the ports and secrets rather
        // than reading them back itself.
        val payload = StartPayload(
            config = cfg,
            adminToken = prefs.adminToken,
            authSecretHex = prefs.authSecretHex,
        )
        val ctx = getApplication<Application>()
        // startForegroundService, not the binder: this is what promotes the
        // service to foreground so Android will not reclaim it.
        ctx.startForegroundService(ServerService.startIntent(ctx, payload.toJson()))
    }

    fun stopServer() {
        val ctx = getApplication<Application>()
        ctx.startService(ServerService.stopIntent(ctx))
    }

    fun reclaimAccount(name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { service?.reclaimAccount(name) ?: "Server is not connected." }
            }
            _message.value = result.getOrElse { "Reclaim failed: ${it.message}" }
        }
    }

    fun loadAccounts(onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { service?.listAccounts() }.getOrNull()
            }
            onResult(json)
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun adminToken(): String = prefs.adminToken

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Creates /sdcard/LunarTear so the user has somewhere obvious to copy into. */
    fun createDataRoot() {
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                paths.dataRoot.isDirectory || paths.dataRoot.mkdirs()
            }
            _message.value = if (created) {
                "Ready. Copy the asset tree into ${AppPaths.displayRoot}."
            } else {
                "Could not create ${AppPaths.displayRoot}. Is \"All files access\" granted?"
            }
            refreshAssets()
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 500
    }
}
