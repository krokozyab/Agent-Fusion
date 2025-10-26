package com.orchestrator.context.watcher

import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight registry that lets other subsystems temporarily pause the active
 * WatcherDaemon while long-running indexing operations (like full rebuilds)
 * manipulate the database.
 */
object WatcherRegistry {
    private val watcherRef = AtomicReference<WatcherDaemon?>()

    fun register(watcher: WatcherDaemon) {
        watcherRef.set(watcher)
    }

    fun unregister(watcher: WatcherDaemon?) {
        watcherRef.compareAndSet(watcher, null)
    }

    suspend fun <T> pauseWhile(block: suspend () -> T): T {
        val watcher = watcherRef.get()
        return watcher?.pauseWhile(block) ?: block()
    }
}
