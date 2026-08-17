package net.lunartear.server;

/**
 * Pushed from the :server process back to the UI process.
 */
oneway interface IServerCallback {
    /**
     * @param state   ordinal of ServerState
     * @param detail  error text when state is ERROR, otherwise a status line
     */
    void onState(int state, String detail);

    /**
     * Log lines, batched. Upstream logging is extremely chatty
     * (internal/service/octo.go:188-192 dumps every request header), so lines
     * are delivered in chunks rather than one Binder transaction each.
     */
    void onLogLines(in String[] lines);
}
