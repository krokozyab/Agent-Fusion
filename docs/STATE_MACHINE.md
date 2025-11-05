# Task State Machine

## State Diagram

```
                    ┌─────────┐
                    │ PENDING │
                    └────┬────┘
                         │
              ┌──────────┴──────────┐
              │                     │
              ▼                     ▼
       ┌─────────────┐         ┌────────┐
       │ IN_PROGRESS │         │ FAILED │ (terminal)
       └──────┬──────┘         └────────┘
              │
    ┌─────────┼─────────┐
    │         │         │
    ▼         ▼         ▼
┌───────────┐ ┌─────────┐ ┌────────┐
│  WAITING  │ │COMPLETED│ │ FAILED │
│   INPUT   │ │         │ │        │
└─────┬─────┘ └─────────┘ └────────┘
      │       (terminal)   (terminal)
      │
      └──────────┐
                 │
                 ▼
          ┌─────────────┐
          │ IN_PROGRESS │
          └─────────────┘
```

## Transition Rules

### From PENDING
- ✅ → IN_PROGRESS (normal start)
- ✅ → FAILED (early failure)
- ❌ → WAITING_INPUT (cannot skip IN_PROGRESS)
- ❌ → COMPLETED (cannot skip IN_PROGRESS)

### From IN_PROGRESS
- ✅ → WAITING_INPUT (needs external input)
- ✅ → COMPLETED (successful completion)
- ✅ → FAILED (execution failure)
- ❌ → PENDING (cannot go backwards)

### From WAITING_INPUT
- ✅ → IN_PROGRESS (resume after input)
- ✅ → FAILED (timeout or error)
- ❌ → COMPLETED (must go through IN_PROGRESS)
- ❌ → PENDING (cannot go backwards)

### From COMPLETED
- ❌ → (no transitions - terminal state)

### From FAILED
- ❌ → (no transitions - terminal state)

## State Descriptions

### PENDING
- Initial state when task is created
- Task is queued but not yet started
- Routing decision may not be made yet

### IN_PROGRESS
- Task is actively being executed
- Workflow is running
- Agent(s) are working on the task

### WAITING_INPUT
- Task execution paused
- Waiting for external input (user, agent, or system)
- Can resume to IN_PROGRESS when input received

### COMPLETED
- Task successfully finished
- Terminal state - no further transitions
- Result is available

### FAILED
- Task execution failed
- Terminal state - no further transitions
- Error information is available

## Usage Examples

```kotlin
// Validate transition
if (StateMachine.isValidTransition(currentState, nextState)) {
    // Proceed
}

// Record transition
StateMachine.transition(
    taskId = taskId,
    from = TaskStatus.PENDING,
    to = TaskStatus.IN_PROGRESS,
    metadata = mapOf("agent" to "agent-1")
)

// Query history
val history = StateMachine.getHistory(taskId)

// Check terminal state
if (StateMachine.isTerminal(currentState)) {
    // No further transitions possible
}
```
