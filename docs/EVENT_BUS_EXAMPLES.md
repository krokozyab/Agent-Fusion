# EventBus Usage Examples

## Basic Usage

### Simple Publish/Subscribe
```kotlin
import com.orchestrator.core.*
import kotlinx.coroutines.*

fun main() = runBlocking {
    val eventBus = EventBus()
    
    // Subscribe
    launch {
        eventBus.subscribe<SystemEvent.TaskCreated>().collect { event ->
            println("Task created: ${event.taskId}")
        }
    }
    
    delay(50) // Let subscription register
    
    // Publish
    eventBus.publish(SystemEvent.TaskCreated(TaskId("task-1")))
    
    delay(100) // Let event process
    eventBus.shutdown()
}
```

### Using Handler Function
```kotlin
fun main() = runBlocking {
    val eventBus = EventBus()
    
    // Register handler
    eventBus.on<SystemEvent.TaskCompleted> { event ->
        println("Task ${event.taskId} completed at ${event.timestamp}")
    }
    
    // Publish events
    eventBus.publish(SystemEvent.TaskCompleted(TaskId("task-1")))
    
    delay(100)
    eventBus.shutdown()
}
```

## Multiple Subscribers

```kotlin
fun setupMonitoring(eventBus: EventBus) {
    // Logger
    eventBus.on<SystemEvent.TaskCreated> { event ->
        logger.info("Task created: ${event.taskId}")
    }
    
    // Metrics
    eventBus.on<SystemEvent.TaskCreated> { event ->
        metrics.increment("tasks.created")
    }
    
    // Database
    eventBus.on<SystemEvent.TaskCreated> { event ->
        database.recordTaskCreation(event.taskId, event.timestamp)
    }
}
```

## Type-Safe Event Filtering

```kotlin
fun main() = runBlocking {
    val eventBus = EventBus()
    
    // Only task events
    launch {
        eventBus.subscribe<SystemEvent.TaskCreated>().collect { event ->
            // event is SystemEvent.TaskCreated
            handleTaskCreation(event.taskId)
        }
    }
    
    // Only agent events
    launch {
        eventBus.subscribe<SystemEvent.AgentStatusChanged>().collect { event ->
            // event is SystemEvent.AgentStatusChanged
            updateAgentStatus(event.agentId, event.status)
        }
    }
    
    // Publish different types
    eventBus.publish(SystemEvent.TaskCreated(TaskId("task-1")))
    eventBus.publish(SystemEvent.AgentStatusChanged(AgentId("agent-1"), AgentStatus.ONLINE))
    
    delay(100)
    eventBus.shutdown()
}
```

## Global Event Bus

```kotlin
// Use singleton for system-wide coordination
object TaskService {
    fun createTask(task: Task) {
        // ... create task
        EventBus.global.publish(SystemEvent.TaskCreated(task.id))
    }
}

object NotificationService {
    init {
        EventBus.global.on<SystemEvent.TaskCreated> { event ->
            sendNotification("New task: ${event.taskId}")
        }
    }
}
```

## Error Handling

```kotlin
fun main() = runBlocking {
    val eventBus = EventBus()
    
    // Handler that might fail
    eventBus.on<SystemEvent.TaskCreated> { event ->
        if (event.taskId.value.isEmpty()) {
            throw IllegalArgumentException("Invalid task ID")
        }
        processTask(event.taskId)
    }
    
    // Other handlers still work
    eventBus.on<SystemEvent.TaskCreated> { event ->
        println("Task created: ${event.taskId}")
    }
    
    // Publish - first handler fails, second succeeds
    eventBus.publish(SystemEvent.TaskCreated(TaskId("")))
    
    delay(100)
    eventBus.shutdown()
}
```

## Flow Operators

```kotlin
fun main() = runBlocking {
    val eventBus = EventBus()
    
    launch {
        eventBus.subscribe<SystemEvent.TaskCreated>()
            .map { it.taskId }
            .filter { it.value.startsWith("important-") }
            .take(5)
            .collect { taskId ->
                println("Important task: $taskId")
            }
    }
    
    delay(50)
    
    repeat(10) { i ->
        val prefix = if (i % 2 == 0) "important-" else "normal-"
        eventBus.publish(SystemEvent.TaskCreated(TaskId("${prefix}task-$i")))
    }
    
    delay(100)
    eventBus.shutdown()
}
```

## Integration with OrchestrationEngine

```kotlin
class OrchestrationEngine(
    private val agentRegistry: AgentRegistry,
    private val eventBus: EventBus = EventBus.global
) {
    fun executeTask(task: Task): WorkflowResult {
        // Publish events
        eventBus.publish(SystemEvent.TaskCreated(task.id))
        
        val result = // ... execute workflow
        
        if (result.success) {
            eventBus.publish(SystemEvent.TaskCompleted(task.id))
        } else {
            eventBus.publish(SystemEvent.TaskFailed(task.id, result.error ?: "Unknown"))
        }
        
        return result
    }
}

// Subscribe to orchestration events
fun setupOrchestrationMonitoring() {
    EventBus.global.on<SystemEvent.TaskCompleted> { event ->
        println("✓ Task ${event.taskId} completed")
    }
    
    EventBus.global.on<SystemEvent.TaskFailed> { event ->
        println("✗ Task ${event.taskId} failed: ${event.error}")
    }
}
```

## Consensus Module Integration

```kotlin
object ConsensusModule {
    fun decide(taskId: TaskId): Outcome {
        val proposals = ProposalManager.getProposals(taskId)
        
        // Notify about proposals
        proposals.forEach { proposal ->
            EventBus.global.publish(
                SystemEvent.ProposalSubmitted(
                    proposal.id,
                    taskId,
                    proposal.agentId
                )
            )
        }
        
        val outcome = // ... run consensus
        
        // Notify about decision
        EventBus.global.publish(
            SystemEvent.DecisionMade(outcome.decisionId, taskId)
        )
        
        return outcome
    }
}
```

## Custom Event Types

```kotlin
// Define custom events
sealed class CustomEvent : Event {
    data class UserLoggedIn(val userId: String) : CustomEvent()
    data class ConfigChanged(val key: String, val value: String) : CustomEvent()
}

// Use with EventBus
fun main() = runBlocking {
    val eventBus = EventBus()
    
    eventBus.on<CustomEvent.UserLoggedIn> { event ->
        println("User ${event.userId} logged in")
    }
    
    eventBus.publish(CustomEvent.UserLoggedIn("user-123"))
    
    delay(100)
    eventBus.shutdown()
}
```

## Testing with EventBus

```kotlin
@Test
fun `task creation publishes event`() = runBlocking {
    val eventBus = EventBus()
    val taskId = TaskId("test-task")
    
    val received = CompletableDeferred<TaskId>()
    
    eventBus.on<SystemEvent.TaskCreated> { event ->
        received.complete(event.taskId)
    }
    
    delay(50)
    eventBus.publish(SystemEvent.TaskCreated(taskId))
    
    val result = withTimeout(1000) { received.await() }
    assertEquals(taskId, result)
    
    eventBus.shutdown()
}
```
