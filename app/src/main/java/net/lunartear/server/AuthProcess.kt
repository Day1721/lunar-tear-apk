package net.lunartear.server

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File

/**
 * Supervises the auth server, which runs as a child process rather than
 * in-process.
 *
 * Why exec instead of the .aar: cmd/auth-server's 254 lines of HTTP handlers and
 * its //go:embed login.html live in package main upstream, so they cannot be
 * imported at any price. It is also the one binary that does not import
 * gen/proto, which makes it small, and it needs no graceful shutdown — a hard
 * kill against its SQLite-WAL store is safe.
 *
 * The binary ships as jniLibs/arm64-v8a/libauthserver.so so Android extracts it
 * into nativeLibraryDir with the execute bit. Android 10+ W^X forbids exec'ing
 * from app data directories; nativeLibraryDir is explicitly allowed. Requires
 * useLegacyPackaging=true, or the file is never extracted at all.
 */
class AuthProcess(private val context: Context, private val paths: AppPaths) {

    @Volatile
    private var process: Process? = null
    private var reader: Thread? = null

    val isAlive: Boolean get() = process?.isAlive == true

    private val binary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libauthserver.so")

    /**
     * @param onLog receives merged stdout/stderr, one line at a time
     * @param onExit called if the child dies on its own
     */
    fun start(
        port: Int,
        secretHex: String,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ) {
        check(process == null) { "auth server already started" }

        if (!binary.canExecute()) {
            throw IllegalStateException(
                "${binary.name} is missing or not executable at ${binary.parent}. " +
                    "Check that useLegacyPackaging=true is set so jniLibs are extracted."
            )
        }
        // cmd/auth-server/main.go:33 has no MkdirAll, so create db/ for it.
        if (!paths.ensureDbDir()) {
            throw IllegalStateException("Could not create ${paths.dbDir}")
        }

        reapOrphan()

        val cmd = listOf(
            binary.absolutePath,
            "--listen", "${ServerConfig.LOOPBACK}:$port",
            "--db", paths.authDb.absolutePath,
            // Persisted, or every restart would invalidate issued tokens.
            "--secret", secretHex,
        )
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        // modernc.org/sqlite resolves scratch files via os.TempDir(), which falls
        // back to a non-writable /tmp on Android.
        pb.environment()["TMPDIR"] = paths.cacheDir.absolutePath
        // Working directory only matters for relative paths; we pass absolutes,
        // but point it somewhere writable regardless.
        pb.directory(context.filesDir)

        val p = pb.start()
        process = p
        writePid(p)

        reader = Thread({
            try {
                p.inputStream.bufferedReader().use { br: BufferedReader ->
                    while (true) {
                        val line = br.readLine() ?: break
                        onLog("[auth] $line")
                    }
                }
            } catch (_: Exception) {
                // Stream closed on shutdown; nothing useful to report.
            }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            if (process === p) {
                onLog("[auth] exited with code $code")
                onExit(code)
            }
        }, "auth-server-log").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val p = process ?: return
        process = null
        // auth-server installs no signal handler at all
        // (cmd/auth-server/main.go:53 is a bare http.ListenAndServe), so destroy()
        // is a SIGTERM it will not handle gracefully. That is fine: WAL makes an
        // abrupt exit safe. Escalate if it lingers.
        p.destroy()
        if (!p.waitFor(GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
        }
        reader?.interrupt()
        reader = null
        paths.authPidFile.delete()
    }

    /**
     * java.lang.Process.pid() is API 33+; on 30-32 the pid sits in a private
     * field of the platform's Process implementation.
     */
    private fun pidOf(p: Process): Int? {
        runCatching {
            val m = Process::class.java.getMethod("pid")
            return (m.invoke(p) as Long).toInt()
        }
        return runCatching {
            val f = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            f.getInt(p)
        }.getOrNull()
    }

    private fun writePid(p: Process) {
        pidOf(p)?.let { pid ->
            runCatching { paths.authPidFile.writeText(pid.toString()) }
        }
    }

    /**
     * If Android's low-memory killer took our process without stop() running, the
     * child may have survived and would now be holding the auth port. Verify the
     * recorded pid still points at our binary before killing it, so a recycled
     * pid can never make us kill something unrelated.
     */
    private fun reapOrphan() {
        val f = paths.authPidFile
        if (!f.exists()) return
        val pid = f.readText().trim().toIntOrNull()
        f.delete()
        if (pid == null || pid <= 0) return

        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return
        if (!cmdline.contains("libauthserver.so")) return

        Log.w(TAG, "reaping orphaned auth server pid=$pid")
        runCatching { android.os.Process.killProcess(pid) }
    }

    private companion object {
        const val TAG = "AuthProcess"
        const val GRACE_MS = 3_000L
    }
}
