package net.lunartear.server

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: ServerViewModel = viewModel()
                AppScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(vm: ServerViewModel) {
    val context = LocalContextCompat()
    val state by vm.state.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val assets by vm.assets.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // Android 13+ hides the ongoing notification without this, which would also
    // hide the Stop action.
    val notifPermission = rememberLauncherForPermission()
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-check the asset tree whenever the user returns from Settings.
    LifecycleResumeEffect { vm.refreshAssets() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Lunar Tear") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            StatusCard(state, detail, config)

            ControlRow(
                state = state,
                onStart = vm::startServer,
                onStop = vm::stopServer,
            )

            PermissionsCard(vm)

            AssetsCard(assets, vm.dataRootDisplay, onCreate = vm::createDataRoot, onRecheck = vm::refreshAssets)

            PortsCard(
                config = config,
                enabled = state == ServerState.STOPPED || state == ServerState.ERROR,
                onChange = vm::updateConfig,
            )

            AccountsCard(vm, enabled = state == ServerState.RUNNING)

            LogCard(logs)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(state: ServerState, detail: String?, config: ServerConfig) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(state.tint(), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(state.label(), style = MaterialTheme.typography.titleMedium)
            }
            if (state == ServerState.RUNNING) {
                Endpoint("gRPC (game)", "${config.publicHost()}:${config.grpcPort}")
                Endpoint("CDN (assets)", "${config.publicHost()}:${config.cdnPort}")
                if (config.authEnabled) {
                    Endpoint("Auth", "${ServerConfig.LOOPBACK}:${config.authPort}")
                }
                Text(
                    "Point the patched client at these. Changing a port means re-patching the client.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == ServerState.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Endpoint(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ControlRow(state: ServerState, onStart: () -> Unit, onStop: () -> Unit) {
    val busy = state == ServerState.STARTING || state == ServerState.STOPPING
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStart,
            enabled = !busy && state != ServerState.RUNNING,
            modifier = Modifier.weight(1f),
        ) { Text("Start") }
        OutlinedButton(
            onClick = onStop,
            enabled = !busy && state == ServerState.RUNNING,
            modifier = Modifier.weight(1f),
        ) { Text("Stop") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun PermissionsCard(vm: ServerViewModel) {
    val context = LocalContextCompat()
    var hasFiles by remember { mutableStateOf(vm.hasAllFilesAccess()) }
    LifecycleResumeEffect { hasFiles = vm.hasAllFilesAccess() }

    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember {
        mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName))
    }
    LifecycleResumeEffect { exempt = power.isIgnoringBatteryOptimizations(context.packageName) }

    if (hasFiles && exempt) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)

            if (!hasFiles) {
                Text(
                    "All files access is required. The server reads the asset tree with plain " +
                        "file I/O, which cannot use the Android folder picker.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) { Text("Grant all files access") }
            }

            if (!exempt) {
                Text(
                    "Exempt from battery optimisation so Doze cannot freeze the server " +
                        "mid-session.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    context.startActivity(
                        @Suppress("BatteryLife")
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) { Text("Ignore battery optimisation") }
            }
        }
    }
}

@Composable
private fun AssetsCard(
    result: AssetValidator.Result?,
    rootDisplay: String,
    onCreate: () -> Unit,
    onRecheck: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Game assets", style = MaterialTheme.typography.titleMedium)
                if (result != null) {
                    Text(
                        if (result.ok) "OK" else "Incomplete",
                        color = if (result.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(rootDisplay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

            if (result != null) {
                if (result.presentTrees.isNotEmpty()) {
                    Text(
                        "Revisions found: ${result.presentTrees.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                result.problems.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!result.ok) {
                    Text(
                        "Expected layout:\n" +
                            "  assets/release/20240404193219.bin.e\n" +
                            "  assets/revisions/0/android/list.bin\n" +
                            "  assets/revisions/0/android/assetbundle/\n" +
                            "  assets/revisions/0/android/resources/",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCreate) { Text("Create folder") }
                OutlinedButton(onClick = onRecheck) { Text("Re-check") }
            }
        }
    }
}

@Composable
private fun PortsCard(config: ServerConfig, enabled: Boolean, onChange: (ServerConfig) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ports and services", style = MaterialTheme.typography.titleMedium)
            if (!enabled) {
                Text(
                    "Stop the server to change these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PortField("gRPC (game server)", config.grpcPort, enabled) { onChange(config.copy(grpcPort = it)) }
            PortField("CDN (assets)", config.cdnPort, enabled) { onChange(config.copy(cdnPort = it)) }

            SwitchRow("Auth server", config.authEnabled, enabled) {
                onChange(config.copy(authEnabled = it))
            }
            if (config.authEnabled) {
                PortField("Auth", config.authPort, enabled) { onChange(config.copy(authPort = it)) }
            } else {
                Text(
                    "Without it, UUID login still works; only account linking and transfer " +
                        "between devices are unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SwitchRow("Allow access from other devices", config.allowLan, enabled) {
                onChange(config.copy(allowLan = it))
            }
            SwitchRow("Disable new registrations", config.noRegister, enabled && config.authEnabled) {
                onChange(config.copy(noRegister = it))
            }
            SwitchRow("Master-data reload webhook", config.adminEnabled, enabled) {
                onChange(config.copy(adminEnabled = it))
            }
            if (config.adminEnabled) {
                PortField("Webhook", config.adminPort, enabled) { onChange(config.copy(adminPort = it)) }
            }
        }
    }
}

@Composable
private fun PortField(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.toIntOrNull()?.let { it < ServerConfig.MIN_PORT } ?: true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AccountsCard(vm: ServerViewModel, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reclaim account", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                Text(
                    "If the game client is reinstalled or its data cleared, it authenticates with " +
                        "a new UUID and creates an empty account. This hands your existing " +
                        "account that new UUID and deletes the empty one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("In-game name") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.reclaimAccount(name.trim()) },
                    enabled = enabled && name.isNotBlank(),
                ) { Text("Reclaim") }
                if (!enabled) {
                    Text(
                        "Start the server first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogCard(logs: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            if (logs.isEmpty()) {
                Text(
                    "Nothing yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// --- small helpers ------------------------------------------------------------

private fun ServerState.label(): String = when (this) {
    ServerState.STOPPED -> "Stopped"
    ServerState.STARTING -> "Starting"
    ServerState.RUNNING -> "Running"
    ServerState.STOPPING -> "Stopping"
    ServerState.ERROR -> "Error"
}

@Composable
private fun ServerState.tint(): Color = when (this) {
    ServerState.RUNNING -> MaterialTheme.colorScheme.primary
    ServerState.ERROR -> MaterialTheme.colorScheme.error
    ServerState.STARTING, ServerState.STOPPING -> MaterialTheme.colorScheme.tertiary
    ServerState.STOPPED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun LocalContextCompat() = androidx.compose.ui.platform.LocalContext.current

@Composable
private fun rememberLauncherForPermission() =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

/** Runs [block] on every ON_RESUME, so returning from Settings refreshes state. */
@Composable
private fun LifecycleResumeEffect(block: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) block()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
