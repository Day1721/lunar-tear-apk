package net.lunartear.server

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR;

    companion object {
        fun fromOrdinal(i: Int): ServerState = entries.getOrElse(i) { STOPPED }
    }
}
