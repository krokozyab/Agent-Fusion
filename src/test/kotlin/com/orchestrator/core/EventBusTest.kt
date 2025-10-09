package com.orchestrator.core

import com.orchestrator.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EventBusTest {
    private lateinit var eventBus: EventBus

    @AfterEach
    fun cleanup() {
        if (::eventBus.isInitialized) {
            eventBus.shutdown()
        }
    }

    @Test
    fun `publish and subscribe to events`() = runBlocking {
        eventBus = EventBus()
        val taskId = TaskId("task-1")
        
        val job = launch {
            val event = eventBus.subscribe<SystemEvent.TaskCreated>().first()
            assertEquals(taskId, event.taskId)
        }
        
        delay(50) // Let subscription register
        eventBus.publish(SystemEvent.TaskCreated(taskId))
        
        withTimeout(1000) { job.join() }
    }

    @Test
    fun `multiple subscribers receive same event`() = runBlocking {
        eventBus = EventBus()
        val taskId = TaskId("task-1")
        val received = AtomicInteger(0)
        
        val jobs = List(3) {
            launch {
                eventBus.subscribe<SystemEvent.TaskCreated>().first()
                received.incrementAndGet()
            }
        }
        
        delay(50)
        eventBus.publish(SystemEvent.TaskCreated(taskId))
        
        withTimeout(1000) { jobs.forEach { it.join() } }
        assertEquals(3, received.get())
    }

    @Test
    fun `subscribers only receive matching event types`() = runBlocking {
        eventBus = EventBus()
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        val taskEvents = mutableListOf<SystemEvent.TaskCreated>()
        val agentEvents = mutableListOf<SystemEvent.AgentStatusChanged>()
        
        val job1 = launch {
            eventBus.subscribe<SystemEvent.TaskCreated>().take(1).collect {
                taskEvents.add(it)
            }
        }
        
        val job2 = launch {
            eventBus.subscribe<SystemEvent.AgentStatusChanged>().take(1).collect {
                agentEvents.add(it)
            }
        }
        
        delay(50)
        eventBus.publish(SystemEvent.TaskCreated(taskId))
        eventBus.publish(SystemEvent.AgentStatusChanged(agentId, AgentStatus.ONLINE))
        
        withTimeout(1000) {
            job1.join()
            job2.join()
        }
        
        assertEquals(1, taskEvents.size)
        assertEquals(1, agentEvents.size)
        assertEquals(taskId, taskEvents[0].taskId)
        assertEquals(agentId, agentEvents[0].agentId)
    }

    @Test
    fun `on handler processes events asynchronously`() = runBlocking {
        eventBus = EventBus()
        val taskId = TaskId("task-1")
        val latch = CountDownLatch(1)
        var receivedTaskId: TaskId? = null
        
        eventBus.on<SystemEvent.TaskCreated> { event ->
            receivedTaskId = event.taskId
            latch.countDown()
        }
        
        delay(50)
        eventBus.publish(SystemEvent.TaskCreated(taskId))
        
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(taskId, receivedTaskId)
    }

    @Test
    fun `publish is non-blocking`() = runBlocking {
        eventBus = EventBus()
        val start = System.currentTimeMillis()
        
        repeat(1000) {
            eventBus.publish(SystemEvent.TaskCreated(TaskId("task-$it")))
        }
        
        val duration = System.currentTimeMillis() - start
        assertTrue(duration < 100, "Publishing 1000 events took ${duration}ms, should be < 100ms")
    }

    @Test
    fun `multiple events can be published and received`() = runBlocking {
        eventBus = EventBus()
        val events = mutableListOf<SystemEvent.TaskCreated>()
        
        val job = launch {
            eventBus.subscribe<SystemEvent.TaskCreated>().take(5).toList(events)
        }
        
        delay(50)
        repeat(5) {
            eventBus.publish(SystemEvent.TaskCreated(TaskId("task-$it")))
        }
        
        withTimeout(1000) { job.join() }
        assertEquals(5, events.size)
    }

    @Test
    fun `event handler errors do not crash bus`() = runBlocking {
        eventBus = EventBus()
        val successLatch = CountDownLatch(1)
        
        // Handler that throws
        eventBus.on<SystemEvent.TaskCreated> { 
            throw RuntimeException("Handler error")
        }
        
        // Handler that succeeds
        eventBus.on<SystemEvent.TaskCreated> { 
            successLatch.countDown()
        }
        
        delay(50)
        eventBus.publish(SystemEvent.TaskCreated(TaskId("task-1")))
        
        assertTrue(successLatch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `shutdown closes all channels`() = runBlocking {
        eventBus = EventBus()
        
        val flow = eventBus.subscribe<SystemEvent.TaskCreated>()
        eventBus.shutdown()
        
        // Should complete immediately as channel is closed
        val result = withTimeoutOrNull(100) {
            flow.toList()
        }
        
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `concurrent publish and subscribe is thread-safe`() = runBlocking {
        eventBus = EventBus()
        val received = AtomicInteger(0)
        val numEvents = 100
        
        // Start multiple subscribers
        val jobs = List(5) {
            launch {
                eventBus.subscribe<SystemEvent.TaskCreated>().take(numEvents).collect {
                    received.incrementAndGet()
                }
            }
        }
        
        delay(50)
        
        // Publish from multiple coroutines
        val publishers = List(10) {
            launch {
                repeat(numEvents / 10) { i ->
                    eventBus.publish(SystemEvent.TaskCreated(TaskId("task-$it-$i")))
                }
            }
        }
        
        withTimeout(5000) {
            publishers.forEach { it.join() }
            jobs.forEach { it.join() }
        }
        
        assertEquals(numEvents * 5, received.get())
    }

    @Test
    fun `global event bus is accessible`() {
        assertNotNull(EventBus.global)
    }

    @Test
    fun `different event types are isolated`() = runBlocking {
        eventBus = EventBus()
        
        val taskCreated = mutableListOf<SystemEvent.TaskCreated>()
        val taskCompleted = mutableListOf<SystemEvent.TaskCompleted>()
        
        val job1 = launch {
            eventBus.subscribe<SystemEvent.TaskCreated>().take(2).toList(taskCreated)
        }
        
        val job2 = launch {
            eventBus.subscribe<SystemEvent.TaskCompleted>().take(2).toList(taskCompleted)
        }
        
        delay(50)
        eventBus.publish(SystemEvent.TaskCreated(TaskId("task-1")))
        eventBus.publish(SystemEvent.TaskCompleted(TaskId("task-1")))
        eventBus.publish(SystemEvent.TaskCreated(TaskId("task-2")))
        eventBus.publish(SystemEvent.TaskCompleted(TaskId("task-2")))
        
        withTimeout(1000) {
            job1.join()
            job2.join()
        }
        
        assertEquals(2, taskCreated.size)
        assertEquals(2, taskCompleted.size)
    }

    @Test
    fun `proposal and decision events work`() = runBlocking {
        eventBus = EventBus()
        val proposalId = ProposalId("prop-1")
        val taskId = TaskId("task-1")
        val agentId = AgentId("agent-1")
        
        val job = launch {
            val event = eventBus.subscribe<SystemEvent.ProposalSubmitted>().first()
            assertEquals(proposalId, event.proposalId)
            assertEquals(taskId, event.taskId)
            assertEquals(agentId, event.agentId)
        }
        
        delay(50)
        eventBus.publish(SystemEvent.ProposalSubmitted(proposalId, taskId, agentId))
        
        withTimeout(1000) { job.join() }
    }

    @Test
    fun `workflow events work`() = runBlocking {
        eventBus = EventBus()
        val taskId = TaskId("task-1")
        
        val startedEvents = mutableListOf<SystemEvent.WorkflowStarted>()
        val completedEvents = mutableListOf<SystemEvent.WorkflowCompleted>()
        
        val job1 = launch {
            eventBus.subscribe<SystemEvent.WorkflowStarted>().take(1).toList(startedEvents)
        }
        
        val job2 = launch {
            eventBus.subscribe<SystemEvent.WorkflowCompleted>().take(1).toList(completedEvents)
        }
        
        delay(50)
        eventBus.publish(SystemEvent.WorkflowStarted(taskId, RoutingStrategy.SOLO))
        eventBus.publish(SystemEvent.WorkflowCompleted(taskId))
        
        withTimeout(1000) {
            job1.join()
            job2.join()
        }
        
        assertEquals(1, startedEvents.size)
        assertEquals(1, completedEvents.size)
        assertEquals(taskId, startedEvents[0].taskId)
        assertEquals(taskId, completedEvents[0].taskId)
    }
}
