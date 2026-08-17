package net.lunartear.server

import android.content.Context
import java.security.SecureRandom

/**
 * Persisted settings. Deliberately plain SharedPreferences: this is read from
 * both the UI process and the :server process, and MODE_MULTI_PROCESS is
 * unreliable, so the service is always handed its config explicitly through the
 * AIDL start() call rather than reading it back here.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lunar_tear", Context.MODE_PRIVATE)

    var config: ServerConfig
        get() = ServerConfig(
            grpcPort = sp.getInt(KEY_GRPC, ServerConfig.DEFAULT_GRPC_PORT),
            cdnPort = sp.getInt(KEY_CDN, ServerConfig.DEFAULT_CDN_PORT),
            authPort = sp.getInt(KEY_AUTH, ServerConfig.DEFAULT_AUTH_PORT),
            adminPort = sp.getInt(KEY_ADMIN_PORT, ServerConfig.DEFAULT_ADMIN_PORT),
            authEnabled = sp.getBoolean(KEY_AUTH_ON, true),
            adminEnabled = sp.getBoolean(KEY_ADMIN_ON, false),
            allowLan = sp.getBoolean(KEY_LAN, false),
            noRegister = sp.getBoolean(KEY_NO_REGISTER, false),
        )
        set(v) = sp.edit()
            .putInt(KEY_GRPC, v.grpcPort)
            .putInt(KEY_CDN, v.cdnPort)
            .putInt(KEY_AUTH, v.authPort)
            .putInt(KEY_ADMIN_PORT, v.adminPort)
            .putBoolean(KEY_AUTH_ON, v.authEnabled)
            .putBoolean(KEY_ADMIN_ON, v.adminEnabled)
            .putBoolean(KEY_LAN, v.allowLan)
            .putBoolean(KEY_NO_REGISTER, v.noRegister)
            .apply()

    /**
     * Set while the user wants the server up. Read after a sticky restart so a
     * process killed in the background comes back on its own, and cleared if that
     * restart fails so a bad asset tree cannot cause a restart loop.
     */
    var shouldRun: Boolean
        get() = sp.getBoolean(KEY_SHOULD_RUN, false)
        set(v) = sp.edit().putBoolean(KEY_SHOULD_RUN, v).apply()

    /**
     * HMAC secret for the auth server's tokens. cmd/auth-server/main.go:24-31
     * generates a random one and prints it when --secret is omitted, which would
     * invalidate every issued token on each restart — so we generate once and
     * pass the same value back every launch.
     */
    val authSecretHex: String
        get() = sp.getString(KEY_AUTH_SECRET, null) ?: newHex(32).also {
            sp.edit().putString(KEY_AUTH_SECRET, it).apply()
        }

    /** Bearer token for the master-data reload webhook. Empty means never bind. */
    val adminToken: String
        get() = sp.getString(KEY_ADMIN_TOKEN, null) ?: newHex(24).also {
            sp.edit().putString(KEY_ADMIN_TOKEN, it).apply()
        }

    private fun newHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_GRPC = "grpc_port"
        const val KEY_CDN = "cdn_port"
        const val KEY_AUTH = "auth_port"
        const val KEY_ADMIN_PORT = "admin_port"
        const val KEY_AUTH_ON = "auth_enabled"
        const val KEY_ADMIN_ON = "admin_enabled"
        const val KEY_LAN = "allow_lan"
        const val KEY_NO_REGISTER = "no_register"
        const val KEY_AUTH_SECRET = "auth_secret_hex"
        const val KEY_ADMIN_TOKEN = "admin_token"
        const val KEY_SHOULD_RUN = "should_run"
    }
}
