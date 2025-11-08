# Task Routing in the Orchestrator Project

The orchestrator handles task routing through a **three-tier system**: **MCP Tools → RoutingModule → Agent Selection**.

## 1. Three Entry Points (MCP Tools)

Users/agents create tasks via three MCP tools, each representing a different routing intent:

### `create_simple_task` (CreateSimpleTaskTool.kt:60-150)
- **Default**: SOLO execution
- **Use case**: Routine tasks, low complexity
- **Key feature**: `skipConsensus` directive to bypass consensus
- **Emergency support**: `immediate`/`isEmergency` flags
- Can be escalated to consensus by RoutingModule if task is high-risk

### `create_consensus_task` (CreateConsensusTaskTool.kt:54-180)
- **Default**: CONSENSUS routing (enforced)
- **Use case**: Critical decisions, high-risk tasks
- **Key feature**: `forceConsensus=true` by default
- **Validation**: Rejects `preventConsensus` directive
- Ensures multi-agent collaboration

### `assign_task` (AssignTaskTool.kt:53-138)
- **Default**: Direct assignment to specific agent
- **Use case**: User knows which agent should handle task
- **Features**:
  - Agent availability checking (OFFLINE → error, BUSY → warning)
  - Dependency validation
  - Metadata enrichment with directives

## 2. Core Routing Logic (RoutingModule.kt:33-93)

The **RoutingModule** orchestrates routing through 4 steps:

### Step 0: Calibration
- Refreshes strategy picker configuration from telemetry
- Learns from past decisions

### Step 1: Classification (line 42)
```kotlin
val classification = TaskClassifier.classify(text)
```
- Analyzes task description
- Determines: complexity (1-10), risk (1-10), critical keywords
- Outputs confidence score

### Step 2: Strategy Selection (line 46)
```kotlin
val strategy = strategyPicker.pickStrategy(task, directive, classification)
```
**Respects user directives:**
- `forceConsensus` → CONSENSUS
- `preventConsensus` → SOLO
- `assignToAgent` → SOLO with specific agent
- `isEmergency` → immediate execution

**Fallback to heuristics** if no directive:
- High risk/complexity → CONSENSUS
- Low complexity → SOLO
- Medium → depends on context

### Step 3: Agent Selection (lines 58-74)
Different logic per strategy:

**SOLO**:
```kotlin
agentSelector.selectAgentForTask(task, directive)
```
- Picks single best agent
- Respects `assignToAgent` directive
- Returns 1 agent

**CONSENSUS/SEQUENTIAL/PARALLEL**:
```kotlin
agentSelector.selectAgentsForConsensus(task, directive, maxAgents=3)
```
- Selects up to 3 agents
- Ensures diversity
- Returns multiple agents

### Step 4: Decision Building (lines 80-92)
Creates `RoutingDecision` with:
- Selected strategy
- Primary agent (first participant)
- All participant agents
- Directive metadata for audit trail

## 3. Routing Strategies

Four strategies available:

| Strategy | Description | Use Case |
|----------|-------------|----------|
| **SOLO** | Single agent handles task | Simple tasks, low risk |
| **CONSENSUS** | Multiple agents review/vote | Critical decisions, high risk |
| **SEQUENTIAL** | Agents work in order (planner → implementer) | Complex multi-step tasks |
| **PARALLEL** | Multiple agents work simultaneously | Exploratory analysis |

## 4. User Directives System

Users can influence routing via directives:

**UserDirective** data class contains:
- `forceConsensus`: Force multi-agent collaboration
- `preventConsensus`: Force solo execution
- `assignToAgent`: Prefer specific agent
- `isEmergency`: Immediate execution
- Confidence scores for each directive

**Directive Flow:**
1. Agent parses user's natural language
2. Detects routing hints ("get Codex to review", "emergency", "skip consensus")
3. Creates UserDirective with confidence scores
4. Passes to RoutingModule
5. RoutingModule respects high-confidence directives

## 5. Key Design Patterns

### Audit Trail
All directives stored in task metadata:
```kotlin
meta["directive.forceConsensus"] = "true"
meta["directive.originalText"] = "user's exact words"
```

### Agent Validation
All three tools validate agents exist in registry:
```kotlin
require(agentRegistry.getAgent(agentId) != null)
```

### Fail-Fast
CreateConsensusTaskTool rejects preventConsensus:
```kotlin
if (preventConsensus) {
    throw IllegalArgumentException("Use create_simple_task instead")
}
```

### Flexibility
Simple tasks CAN be escalated to consensus if RoutingModule detects high risk

## Summary

The routing system provides **three levels of control**:

1. **Explicit** (user chooses tool): `create_consensus_task` vs `create_simple_task` vs `assign_task`
2. **Directive-based** (user hints in natural language): "get Codex to review", "emergency"
3. **Automatic** (heuristics): RoutingModule analyzes complexity/risk and escalates if needed

This flexible architecture balances user control with intelligent automation.

## Key Files

- `src/main/kotlin/com/orchestrator/mcp/tools/CreateSimpleTaskTool.kt`
- `src/main/kotlin/com/orchestrator/mcp/tools/CreateConsensusTaskTool.kt`
- `src/main/kotlin/com/orchestrator/mcp/tools/AssignTaskTool.kt`
- `src/main/kotlin/com/orchestrator/modules/routing/RoutingModule.kt`
- `src/main/kotlin/com/orchestrator/modules/routing/TaskClassifier.kt`
- `src/main/kotlin/com/orchestrator/modules/routing/StrategyPicker.kt`
- `src/main/kotlin/com/orchestrator/modules/routing/AgentSelector.kt`