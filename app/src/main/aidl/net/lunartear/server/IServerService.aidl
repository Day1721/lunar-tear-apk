package net.lunartear.server;

import net.lunartear.server.IServerCallback;

interface IServerService {
    /** ordinal of ServerState */
    int getState();

    /** Error text if the last start attempt failed, else null. */
    String getLastError();

    /** Buffered log history, newline-joined, oldest first. */
    String getLogSnapshot();

    void registerCallback(IServerCallback cb);
    void unregisterCallback(IServerCallback cb);

    // Deliberately no start()/stop() here. Starting over the binder would not
    // promote the service to the foreground, so the UI drives the lifecycle with
    // startForegroundService(ACTION_START) / startService(ACTION_STOP) instead.
    // Exposing both would invite the wrong one being used.

    /** Reassigns an account's UUID to the most recently connected client. */
    String reclaimAccount(String name);

    /** JSON array of {userId, name, uuid}. */
    String listAccounts();
}
