package net.lunartear.server

import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * User-facing server settings.
 *
 * Port defaults match upstream's wizard (cmd/wizard/main.go:45-49) rather than
 * the raw binaries: the game server's own default of 0.0.0.0:443 is a privileged
 * port an unprivileged Android app cannot bind. 8003/8080/3000/8082 are all
 * above 1024 and unclaimed by the Android platform.
 *
 * These must match whatever host:port the patched game client was built against.
 */
data class ServerConfig(
    val grpcPort: Int = DEFAULT_GRPC_PORT,
    val cdnPort: Int = DEFAULT_CDN_PORT,
    val authPort: Int = DEFAULT_AUTH_PORT,
    val adminPort: Int = DEFAULT_ADMIN_PORT,
    val authEnabled: Boolean = true,
    val adminEnabled: Boolean = false,
    /** Bind 0.0.0.0 as well as loopback, so another device on the LAN can connect. */
    val allowLan: Boolean = false,
    val noRegister: Boolean = false,
) {
    val bindHost: String get() = if (allowLan) "0.0.0.0" else LOOPBACK

    /**
     * The address advertised to the client via ConfigService and baked into
     * list.bin. Loopback for the on-device case; when LAN access is on we
     * advertise the LAN IPv4 so a second device can reach us.
     */
    fun publicHost(): String = if (allowLan) (lanIpv4() ?: LOOPBACK) else LOOPBACK

    /** Ports the user can collide; checked before start. */
    fun validate(): String? {
        val ports = buildList {
            add("gRPC" to grpcPort)
            add("CDN" to cdnPort)
            if (authEnabled) add("auth" to authPort)
            if (adminEnabled) add("admin" to adminPort)
        }
        for ((label, p) in ports) {
            if (p < MIN_PORT || p > MAX_PORT) {
                return "$label port $p is out of range ($MIN_PORT-$MAX_PORT). " +
                    "Android apps cannot bind ports below $MIN_PORT."
            }
        }
        val dupes = ports.groupBy { it.second }.filterValues { it.size > 1 }
        if (dupes.isNotEmpty()) {
            val (port, who) = dupes.entries.first()
            return "Port $port is used by more than one service (${who.joinToString { it.first }})."
        }
        if (noRegister && !authEnabled) {
            // internal/service/user.go:43-51 denies RegisterUser outright when
            // noRegister is set, so the auth server is the only remaining way in.
            return "\"Disable new registrations\" requires the auth server, " +
                "otherwise no client can ever log in."
        }
        // cmd/octo-cdn/main.go:27-35 rewrites list.bin in place with a 43-byte
        // URL; anything longer silently disables the rewrite.
        val publicUrl = "http://${publicHost()}:$cdnPort/"
        if (publicUrl.length > RESOURCES_URL_LEN - 1) {
            return "Public CDN address \"$publicUrl\" is too long for list.bin " +
                "rewriting (max ${RESOURCES_URL_LEN - 1} characters)."
        }
        return null
    }

    /** Serialized form of the Go `config` struct in goglue/mobile/config.go. */
    fun toGoConfigJson(paths: AppPaths, adminToken: String): String {
        val publicHost = publicHost()
        return JSONObject().apply {
            put("assetsRoot", paths.dataRoot.absolutePath)
            put("gameDB", paths.gameDb.absolutePath)
            put("cacheDir", paths.cacheDir.absolutePath)
            put("grpcListen", "$bindHost:$grpcPort")
            put("grpcPublicAddr", "$publicHost:$grpcPort")
            put("cdnListen", "$bindHost:$cdnPort")
            put("cdnPublicAddr", "$publicHost:$cdnPort")
            // Numeric, never "localhost": with CGO_ENABLED=0 Go uses its pure
            // resolver, and a literal IP skips name resolution entirely.
            put("authURL", if (authEnabled) "http://$LOOPBACK:$authPort" else "")
            put("adminListen", if (adminEnabled) "$LOOPBACK:$adminPort" else "")
            put("adminToken", if (adminEnabled) adminToken else "")
            put("noRegister", noRegister)
        }.toString()
    }

    fun endpointSummary(): String {
        val host = publicHost()
        return buildString {
            append("gRPC $host:$grpcPort  ·  CDN $host:$cdnPort")
            if (authEnabled) append("  ·  auth $LOOPBACK:$authPort")
        }
    }

    companion object {
        const val DEFAULT_GRPC_PORT = 8003
        const val DEFAULT_CDN_PORT = 8080
        const val DEFAULT_AUTH_PORT = 3000
        const val DEFAULT_ADMIN_PORT = 8082
        const val LOOPBACK = "127.0.0.1"
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** See cmd/octo-cdn/main.go:29 — the in-place list.bin URL is 43 bytes. */
        const val RESOURCES_URL_LEN = 43

        /**
         * Mirrors the intent of the wizard's detectLANIP (cmd/wizard/main.go:752),
         * minus the desktop-only interface-name filtering.
         */
        fun lanIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
