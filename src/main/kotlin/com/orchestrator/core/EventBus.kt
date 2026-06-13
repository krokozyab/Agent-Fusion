package com.orchestrator.core

import com.orchestrator.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Base interface for all events in the system.
 */
interface Event {
    val timestamp: Instant get() = Instant.now()
}

/**
 * System-wide events.
 */
sealed class SystemEvent : Event {
    data class TaskCreated(val taskId: TaskId, override val timestamp: Instant = Instant.now()) : SystemEvent()
    data class TaskUpdated(val taskId: TaskId, override val timestamp: Instant = Instant.now()) : SystemEvent()
    data class TaskCompleted(val taskId: TaskId, override val timestamp: Instant = Instant.now()) : SystemEvent()
    data class TaskFailed(val taskId: TaskId, val error: String, override val timestamp: Instant = Instant.now()) : SystemEvent()
    
    data class ProposalSubmitted(val proposalId: ProposalId, val taskId: TaskId, val agentId: AgentId, override val timestamp: Instant = Instant.now()) : SystemEvent()
    data class DecisionMade(val decisionId: DecisionId, val taskId: TaskId, override val timestamp: Instant = Instant.now()) : SystemEvent()
    
    data class AgentStatusChanged(val agentId: AgentId, val status: AgentStatus, override val timestamp: Instant = Instant.now()) : SystemEvent()
    
    data class WorkflowStarted(val taskId: TaskId, val strategy: RoutingStrategy, override val timestamp: Instant = Instant.now()) : SystemEvent()
    data class WorkflowCompleted(val taskId: TaskId, override val timestamp: Instant = Instant.now()) : SystemEvent()
    data class WorkflowFailed(val taskId: TaskId, val error: String, override val timestamp: Instant = Instant.now()) : SystemEvent()
}

/**
 * Coroutine-based event bus for async, non-blocking event handling.
 * 
 * Features:
 * - Type-safe event subscription
 * - Async event processing
 * - Multiple subscribers per event type
 * - No blocking on publish
 * - Automatic cleanup on scope cancellation
 */
class EventBus(
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    val bufferSize: Int = Channel.UNLIMITED
) {
    // CopyOnWriteArrayList: publish iterates these lists concurrently with subscribe/unsubscribe
    // (add/remove). A plain ArrayList would throw ConcurrentModificationException, and since
    // publish is called from workflow checkpoints, that exception would crash task execution.
    @PublishedApi
    internal val channels = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Channel<Event>>>()

    /**
     * Publish an event to all subscribers. Non-blocking and never throws to the caller.
     *
     * Delivery is polymorphic: a published event reaches subscribers registered for its concrete
     * class AND for any supertype/interface (e.g. `on<SystemEvent>` receives a `TaskCreated`).
     * Previously publish looked up only the concrete class while subscribe registered by the
     * subscribed type, so supertype subscriptions silently received nothing.
     */
    fun publish(event: Event) {
        val eventClass = event::class.java
        channels.forEach { (registeredClass, channelList) ->
            if (registeredClass.isAssignableFrom(eventClass)) {
                channelList.forEach { channel ->
                    runCatching { channel.trySend(event) }
                }
            }
        }
    }

    /**
     * Subscribe to events of a specific type. Returns a Flow for consumption.
     */
    inline fun <reified T : Event> subscribe(): Flow<T> {
        val channel = Channel<Event>(bufferSize)
        val eventClass = T::class.java
        
        channels.computeIfAbsent(eventClass) { CopyOnWriteArrayList() }.add(channel)
        
        return channel.receiveAsFlow()
            .filterIsInstance<T>()
            .onCompletion {
                channels[eventClass]?.remove(channel)
                channel.close()
            }
    }

    /**
     * Subscribe and handle events with a suspending function.
     */
    inline fun <reified T : Event> on(crossinline handler: suspend (T) -> Unit): Job {
        return scope.launch {
            subscribe<T>().collect { event ->
                try {
                    handler(event)
                } catch (e: Exception) {
                    // Log but don't crash
                    System.err.println("Event handler error: ${e.message}")
                }
            }
        }
    }

    /**
     * Shutdown the event bus and cancel all subscriptions.
     */
    fun shutdown() {
        channels.values.flatten().forEach { it.close() }
        channels.clear()
        scope.cancel()
    }

    /**
     * Get subscriber count for a specific event type.
     */
    fun subscriberCount(eventClass: Class<*>): Int {
        return channels[eventClass]?.size ?: 0
    }

    companion object {
        /**
         * Global event bus instance for convenience.
         */
        val global = EventBus()
    }
}
