package net.lunartear.server

import org.json.JSONObject
import java.io.File

/**
 * Everything the `:server` process needs in order to start, passed explicitly
 * from the UI process.
 *
 * This exists because SharedPreferences is per-process: each process keeps its
 * own in-memory cache of the same file, so a value the UI writes is not reliably
 * visible to the service. Reading config there would mean the Go servers get the
 * ports from this payload while the auth child got stale ones from Prefs —
 * binding a different port than the game server was told to use, with no error.
 *
 * So the UI process is the single source of truth, and the service persists the
 * last payload to disk itself (single writer) for the sticky-restart path.
 */
data class StartPayload(
    val config: ServerConfig,
    /** Bearer token for the master-data reload webhook. */
    val adminToken: String,
    /**
     * HMAC secret for auth-server tokens. Generated once and reused, or every
     * restart would invalidate issued tokens (cmd/auth-server/main.go:24-31).
     */
    val authSecretHex: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("grpcPort", config.grpcPort)
        put("cdnPort", config.cdnPort)
        put("authPort", config.authPort)
        put("adminPort", config.adminPort)
        put("authEnabled", config.authEnabled)
        put("adminEnabled", config.adminEnabled)
        put("allowLan", config.allowLan)
        put("noRegister", config.noRegister)
        put("adminToken", adminToken)
        put("authSecretHex", authSecretHex)
    }.toString()

    companion object {
        fun fromJson(json: String): StartPayload {
            val o = JSONObject(json)
            return StartPayload(
                config = ServerConfig(
                    grpcPort = o.getInt("grpcPort"),
                    cdnPort = o.getInt("cdnPort"),
                    authPort = o.getInt("authPort"),
                    adminPort = o.getInt("adminPort"),
                    authEnabled = o.getBoolean("authEnabled"),
                    adminEnabled = o.getBoolean("adminEnabled"),
                    allowLan = o.getBoolean("allowLan"),
                    noRegister = o.getBoolean("noRegister"),
                ),
                adminToken = o.optString("adminToken", ""),
                authSecretHex = o.optString("authSecretHex", ""),
            )
        }

        /** Written and read only by the service process, so no cache skew. */
        fun cacheFile(context: android.content.Context): File =
            File(context.filesDir, "last-start.json")
    }
}
