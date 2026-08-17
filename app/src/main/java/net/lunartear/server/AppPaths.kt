package net.lunartear.server

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where everything lives on device.
 *
 * The split is deliberate: the asset tree sits on shared storage where the user
 * can actually put multi-GB files, while the SQLite databases stay on
 * app-internal storage. SQLite WAL on FUSE-backed emulated storage is
 * unreliable, and the databases are small.
 */
class AppPaths(context: Context) {

    /**
     * Fixed at /sdcard/LunarTear. Not a SAF-picked location: Go's os package and
     * http.ServeFile (internal/service/octo.go:448) cannot open content:// URIs,
     * so the server needs a real POSIX path. Must be writable too —
     * internal/runtime/holder.go:85 calls os.Chtimes on the master-data file.
     */
    val dataRoot: File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    val dbDir: File = File(context.filesDir, "db")

    /** Becomes TMPDIR for modernc.org/sqlite's scratch files. */
    val cacheDir: File = context.cacheDir

    val gameDb: File get() = File(dbDir, "game.db")
    val authDb: File get() = File(dbDir, "auth.db")

    /** Records the auth-server child's pid so orphans can be reaped. */
    val authPidFile: File = File(context.filesDir, "auth.pid")

    /**
     * cmd/auth-server/main.go:33 opens its database without MkdirAll, unlike
     * internal/database.Open — so the directory has to exist beforehand.
     */
    fun ensureDbDir(): Boolean = dbDir.isDirectory || dbDir.mkdirs()

    companion object {
        const val DIR_NAME = "LunarTear"
        val displayRoot: String get() = "/sdcard/$DIR_NAME"
    }
}
