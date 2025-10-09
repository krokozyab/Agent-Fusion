package com.orchestrator.core

import com.orchestrator.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    @PublishedApi
    internal val channels = ConcurrentHashMap<Class<*>, MutableList<Channel<Event>>>()

    /**
     * Publish an event to all subscribers. Non-blocking.
     */
    fun publish(event: Event) {
        val eventClass = event::class.java
        channels[eventClass]?.forEach { channel ->
            channel.trySend(event)
        }
    }

    /**
     * Subscribe to events of a specific type. Returns a Flow for consumption.
     */
    inline fun <reified T : Event> subscribe(): Flow<T> {
        val channel = Channel<Event>(bufferSize)
        val eventClass = T::class.java
        
        channels.computeIfAbsent(eventClass) { mutableListOf() }.add(channel)
        
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
