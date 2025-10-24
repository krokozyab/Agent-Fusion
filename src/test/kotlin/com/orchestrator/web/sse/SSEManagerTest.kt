package com.orchestrator.web.sse

import java.time.Instant
import java.time.Duration as JavaDuration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class SSEManagerTest {

    @Test
    fun `broadcast delivers events to all subscribers`() = runBlocking {
        val clock = MutableClock()
        val manager = SSEManager(this, keepAliveInterval = 30.seconds, staleThreshold = JavaDuration.ofSeconds(2), clock = clock::now)
        val senderA = RecordingSender()
        val senderB = RecordingSender()

        manager.subscribe("A", senderA)
        manager.subscribe("B", senderB)
        delay(10)
        clock.advanceByMillis(10)

        assertEquals(SSEEventType.CONNECTED, senderA.events.first().type)
        assertEquals(SSEEventType.CONNECTED, senderB.events.first().type)

        manager.broadcast(SSEEvent.message("hello", timestamp = clock.now()))
        delay(10)
        clock.advanceByMillis(10)

        assertEquals("hello", senderA.events.last().data)
        assertEquals(2, senderA.events.size)
        assertEquals(2, senderB.events.size)
        manager.shutdown()
    }

    @Test
    fun `unsubscribe removes connection`() = runBlocking {
        val clock = MutableClock()
        val manager = SSEManager(this, keepAliveInterval = 30.seconds, staleThreshold = JavaDuration.ofSeconds(2), clock = clock::now)
        val sender = RecordingSender()
        manager.subscribe("solo", sender)
        delay(10)
        clock.advanceByMillis(10)

        manager.unsubscribe("solo")
        delay(10)
        clock.advanceByMillis(10)

        manager.broadcast(SSEEvent.message("late", timestamp = clock.now()))
        delay(10)
        clock.advanceByMillis(10)

        val lateEvents = sender.events.count { it.type == SSEEventType.MESSAGE && it.data == "late" }
        assertEquals(0, lateEvents)
        assertEquals(0, manager.activeConnections)
        manager.shutdown()
    }

    @Test
    fun `keep-alive events are emitted`() = runBlocking {
        val clock = MutableClock()
        val manager = SSEManager(this, keepAliveInterval = 25.milliseconds, staleThreshold = JavaDuration.ofSeconds(2), clock = clock::now)
        val sender = RecordingSender()
        manager.subscribe("ping", sender)
        delay(10)

        repeat(3) {
            delay(30)
            clock.advanceByMillis(30)
        }

        assertTrue(sender.events.any { it.type == SSEEventType.KEEP_ALIVE })
        manager.shutdown()
    }

    private class RecordingSender : SSEConnection.Sender {
        val events = ArrayDeque<SSEEvent>()
        private val maxEvents = 256
        override suspend fun send(event: SSEEvent) {
            if (events.size >= maxEvents) {
                events.removeFirst()
            }
            events.addLast(event)
        }
    }

    private class MutableClock(initial: Instant = Instant.parse("2025-01-01T00:00:00Z")) {
        private val instant = AtomicReference(initial)
        fun now(): Instant = instant.get()
        fun advanceByMillis(millis: Long) {
            instant.updateAndGet { it.plusMillis(millis) }
        }
    }
}
