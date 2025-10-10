
# Agent Instructions: Working with the Orchestrator

**Target Audience**: AI Agents (Claude Code, Codex CLI, future agents)
**Purpose**: Step-by-step guide for agents to collaborate through the orchestrator
**User Context**: Users will provide these instructions to agents before starting work

---

## Quick Reference for Agents

### Core Principle
You are **one of multiple AI agents** connected to a shared orchestrator. When tasks require collaboration, you create tasks in the orchestrator, other agents provide input, and you continue with full context.

### Your Role
- **Primary Agent**: You receive user requests and coordinate work
- **Secondary Agent**: You provide specialized input when requested
- **Always Collaborative**: Never work in isolation on critical tasks

### Task Metadata Checklist
- Always populate `title` and a detailed `description` so the next agent has full context without re-asking the user.
- Set `roleInWorkflow` (e.g., `EXECUTION`, `REVIEW`, `FOLLOW_UP`) when creating or handing off tasks so queues clearly show *why* the work exists.
- Mirror that intent when submitting proposals—state whether your input is an initial solution, review feedback, or escalation so the orchestrator routes follow-up correctly.

---

## Table of Contents

1. [Understanding the Orchestrator](#understanding-the-orchestrator)
2. [When to Use the Orchestrator](#when-to-use-the-orchestrator)
3. [MCP Tools Available](#mcp-tools-available)
4. [Workflow Patterns](#workflow-patterns)
5. [Directive Reference](#directive-reference)
6. [User Directive Detection](#user-directive-detection)
7. [Best Practices](#best-practices)
8. [Common Scenarios](#common-scenarios)
9. [Error Handling](#error-handling)

---

## Understanding the Orchestrator

### What It Is
- **Shared state server** running on `localhost:3000`
- **Task registry** for tracking collaboration
- **Proposal storage** for agent inputs
- **Context manager** preserving full conversation history
- **Decision recorder** for consensus outcomes

### What It's NOT
- NOT an automated dispatcher (user controls switches)
- NOT a replacement for your judgment
- NOT required for simple tasks

### Your Connection
You are connected as an **MCP client** to the orchestrator's **HTTP MCP server**.

```
You (Agent) → HTTP → Orchestrator (localhost:3000) ← HTTP ← Other Agent
```

---

## When to Use the Orchestrator

### Automatic Triggers (Analyze These)

**Create consensus task when:**
```
Complexity >= 7 OR
Risk >= 7 OR
Critical keywords detected: "security", "auth", "payment", "data migration", "critical"
```

**Complexity indicators (1-10 scale):**
- Architecture decisions → 8-9
- New system design → 7-9
- Multi-component refactoring → 6-8
- Simple bug fix → 1-3
- Documentation → 1-2

**Risk indicators (1-10 scale):**
- Security-related → 9-10
- Authentication/authorization → 9
- Payment processing → 10
- Data migration → 8-9
- API changes → 6-7
- UI tweaks → 1-3

### User Directive Detection

**ALWAYS check user requests for these directives:**

#### Force Consensus Keywords
```
"get {agent}'s input"
"need consensus"
"want {agent} to review"
"check with {agent}"
"ask {agent} about"
"have {agent} look at"
"consensus required"
```

#### Prevent Consensus Keywords
```
"solo"
"no consensus"
"skip consensus"
"skip review"
"just implement"
"emergency"
"production down"
"hotfix"
"NOW"
```

#### Agent Assignment Keywords
```
"ask Codex" / "ask Claude"
"Codex, ..." / "Claude, ..."
"have Codex" / "have Claude"
"get Codex to" / "get Claude to"
```

---

## MCP Tools Available

### Task Creation

#### `create_consensus_task`
**When**: Need input from another agent
**Use**: Critical/complex tasks requiring collaboration

```json
{
  "title": "Short descriptive title",
  "description": "Detailed context, requirements, constraints",
  "roleInWorkflow": "EXECUTION | REVIEW | FOLLOW_UP",
  "type": "IMPLEMENTATION | ARCHITECTURE | REVIEW | RESEARCH | etc.",
  "complexity": 1-10,
  "risk": 1-10,
  "directives": {
    "forceConsensus": true,  // User explicitly requested
    "skipConsensus": false,
    "assignToAgent": "codex-cli",
    "isEmergency": false,
    "notes": "User context",
    "originalText": "User's exact words"
  }
}
```

**Returns**: `{ taskId, status, routing, primaryAgentId, participantAgentIds }`

#### `create_simple_task`
**When**: Solo execution, no consensus needed
**Use**: Simple/low-risk tasks

```json
{
  "title": "Task description",
  "description": "Details",
  "roleInWorkflow": "EXECUTION | REVIEW | FOLLOW_UP",
  "type": "BUGFIX | DOCUMENTATION | etc.",
  "skipConsensus": true,  // User forced solo
  "directives": { "notes": "Why skipped" }
}
```

#### `assign_task`
**When**: User specifies which agent should handle it
**Use**: "Ask Codex to design schema"

```json
{
  "title": "Design database schema",
  "description": "Multi-tenant system...",
  "targetAgent": "codex-cli"
}
```

### Task Management

#### `get_pending_tasks`
**When**: User says "check pending tasks"
**Use**: Find work waiting for your input

```json
{
  "agentId": "claude-code"  // Optional, defaults to you
}
```

**Returns**: List of tasks with `{ id, title, description, status, createdAt }`

#### `get_task_status`
**When**: Check progress of specific task
**Use**: "What's the status of task-123?"

```json
{
  "taskId": "task-123"
}
```

**Returns**: `{ taskId, status, type, assignees, createdAt, updatedAt }`

#### `continue_task`
**When**: Resume work after another agent provided input
**Use**: Get full context to proceed

```json
{
  "taskId": "task-123"
}
```

**Returns**: Full task context including:
- Task details
- All proposals submitted
- Conversation history
- File changes

### Proposal Submission

#### `submit_input`
**When**: Providing analysis/plan/review for a task
**Use**: Submit your work product

```json
{
  "taskId": "task-123",
  "agentId": "codex-cli",
  "inputType": "ARCHITECTURAL_PLAN | CODE_REVIEW | RESEARCH_SUMMARY | etc.",
  "confidence": 0.85,  // 0.0-1.0 how confident you are
  "content": "Your detailed analysis/plan/code/review (start with the role you're playing, e.g., 'REVIEW_FEEDBACK: ...')"
}
```

**Important**: Do NOT call `complete_task` after submitting - let the requesting agent complete it after reviewing your input. Clearly label the role of your proposal in the content body (`INITIAL_SOLUTION`, `REVIEW_FEEDBACK`, `ESCALATION`, etc.) so follow-up agents know how to act on it.

#### `complete_task`
**When**: Task is fully done (only if YOU created it)
**Use**: Mark task as completed

```json
{
  "taskId": "task-123",
  "resultSummary": "Brief summary of outcome",
  "decision": {
    "considered": [...],  // Proposals evaluated
    "selected": [...],  // Winning proposals
    "agreementRate": 0.95,
    "rationale": "Why this approach won"
  }
}
```

**Critical**: Only call this if you are the primary agent who created the task. If you're providing input (secondary agent), just use `submit_input` and stop.

---

## Workflow Patterns

### Pattern 1: Solo (You Handle Alone)

```
User: "Fix the login timeout bug"
  ↓
You analyze: complexity=3, risk=2, no critical keywords
  ↓
Decision: SOLO (no orchestrator needed)
  ↓
You implement and complete
```

**No orchestrator calls needed.**

### Pattern 2: Consensus (Need Other Agent)

```
User: "Build OAuth2 authentication system"
  ↓
You analyze: complexity=8, risk=9, keyword="authentication"
  ↓
Decision: CONSENSUS needed
  ↓
You: create_consensus_task(...)
  ↓
Orchestrator: Returns task-123, status=PENDING
  ↓
You tell user: "Task #123 created. Switch to Codex CLI for architectural plan."
  ↓
[User switches to other agent]
  ↓
Other agent: get_pending_tasks() → finds task-123
Other agent: Analyzes and submits architectural plan
Other agent: submit_input(taskId=123, inputType=ARCHITECTURAL_PLAN, ...)
  ↓
[User switches back to you]
  ↓
User: "Continue task 123"
  ↓
You: continue_task(123) → receives full plan
You: Implement based on plan
You: complete_task(123, decision={...})
```

### Pattern 3: Review (Sequential Handoff)

```
You: Implement feature X
  ↓
You: create_simple_task("Review my implementation")
You: assign_task(targetAgent="codex-cli")
  ↓
[User switches to Codex]
  ↓
Codex: get_pending_tasks() → finds review task
Codex: Reviews code, submits feedback
Codex: submit_input(inputType=CODE_REVIEW, ...)
  ↓
[User switches back]
  ↓
You: continue_task(...) → receives feedback
You: Apply fixes based on review
You: complete_task(...)
```

### Pattern 4: User-Forced Consensus

```
User: "Refactor validation logic (get Codex to review)"
  ↓
You detect: directive="get Codex to review" → forceConsensus=true
  ↓
You: create_consensus_task(
  description="Refactor validation logic",
  directives={ forceConsensus: true, originalText: "..." }
)
  ↓
[Continue consensus workflow as in Pattern 2]
```

### Pattern 5: Emergency Bypass

```
User: "Fix auth bug NOW - production down, skip consensus"
  ↓
You detect: "production down", "skip consensus" → preventConsensus=true, isEmergency=true
  ↓
You: Log warning about consensus bypass
You: Implement fix immediately (solo)
You: Suggest post-fix review task: "Create follow-up for Codex review? [Y/n]"
```

---

## Directive Reference

### Available Directives

Directives are internal routing controls that agents set based on user intent detected from natural language. **Users do not set these directly** - agents parse user requests and populate directives automatically.

| Directive | Tool Availability | Purpose | Agent Detection Triggers |
|-----------|-------------------|---------|-------------------------|
| `forceConsensus` | consensus tasks | Force multi-agent collaboration | "get {agent}'s input", "need consensus", "want {agent} to review" |
| `preventConsensus` | consensus tasks | Override consensus requirement | "skip consensus", "no review needed" (use with emergency) |
| `skipConsensus` | simple tasks | Bypass consensus for simple work | "solo", "just do it", "quick fix" |
| `assignToAgent` | all task types | Route to specific agent | "ask Codex", "have Claude", "Codex, please..." |
| `isEmergency` | all task types | Flag for immediate execution + audit | "production down", "NOW", "hotfix", "urgent" |
| `immediate` | all task types | Alias for isEmergency | "ASAP", "immediate", "right now" |
| `originalText` | all task types | Preserve exact user wording | Auto-populated with user's request verbatim |
| `notes` | all task types | Additional agent context | Agent's interpretation/reasoning for routing choice |

### Directive Usage by Tool

#### `create_consensus_task`
```json
{
  "directives": {
    "forceConsensus": true,      // User explicitly requested consensus
    "preventConsensus": false,   // Override auto-consensus (use with isEmergency)
    "assignToAgent": "codex-cli", // Optional: which agent should respond
    "isEmergency": false,        // Flag critical/production issues
    "originalText": "...",       // User's exact words
    "notes": "..."              // Why you chose this routing
  }
}
```

#### `create_simple_task`
```json
{
  "directives": {
    "skipConsensus": true,       // User wants solo execution
    "assignToAgent": "me",       // Optional: self-assignment
    "isEmergency": false,
    "immediate": false,          // Alias for isEmergency
    "originalText": "...",
    "notes": "..."
  }
}
```

#### `assign_task`
```json
{
  "directives": {
    "isEmergency": false,
    "immediate": false,
    "originalText": "...",
    "notes": "Assigned to {agent} because..."
  }
}
```

### When to Use Each Directive

#### `forceConsensus`
- **Set when**: User explicitly requests another agent's input on a task you'd normally handle solo
- **Example**: "Refactor validation (get Codex to review)" → You'd skip consensus but user wants it
- **Effect**: Creates consensus task even if complexity/risk is low

#### `preventConsensus`
- **Set when**: User overrides your consensus recommendation during emergency
- **Example**: "Auth bug - production down, skip consensus" → High-risk but user forces solo
- **Effect**: Bypasses consensus requirement; typically paired with `isEmergency: true`
- **Important**: Log this decision for post-incident review

#### `skipConsensus`
- **Set when**: User wants quick solo execution for routine work
- **Example**: "Fix typo in README, solo mode" → Simple task, user confirms solo
- **Effect**: Prevents any consensus checks

#### `assignToAgent`
- **Set when**: User names a specific agent for the task
- **Example**: "Ask Codex to design the schema" → Direct agent call-out
- **Effect**: Routes to specified agent; other agents won't be considered
- **Values**: `"claude-code"`, `"codex-cli"`, or other configured agent IDs

#### `isEmergency` / `immediate`
- **Set when**: Production issues, critical bugs, time-sensitive fixes
- **Example**: "Production down - fix NOW" → Urgency detected
- **Effect**:
  - Flags task for audit trail
  - May bypass normal workflow
  - Logged for post-mortem analysis
- **Note**: Often combined with `preventConsensus`

#### `originalText`
- **Set when**: Always - auto-populate with user's exact request
- **Purpose**:
  - Preserve context for other agents
  - Audit trail of actual user intent
  - Helps resolve ambiguities later
- **Example**: User says "Fix that annoying bug ASAP" → Store verbatim, not your interpretation

#### `notes`
- **Set when**: You want to explain your routing decision to other agents
- **Purpose**:
  - Document why you chose this routing
  - Explain assumptions or constraints
  - Provide additional context not in description
- **Example**: `"User requested review despite low complexity - adding for quality assurance"`

---

## User Directive Detection

### Implementation Pattern

**ALWAYS parse user directives FIRST before analyzing:**

```
1. Parse user request for directives
2. Check for explicit directives (force/prevent consensus, agent assignment, emergency)
3. If directive found → follow user's explicit instruction
4. If no directive → analyze complexity/risk and decide autonomously
5. If auto-detected high risk → ask user for confirmation
```

### Detection Algorithm (Pseudocode)

```kotlin
fun handleUserRequest(request: String) {
    // 1. PARSE DIRECTIVES FIRST
    val directive = parseDirectives(request)

    // 2. HANDLE EXPLICIT USER DIRECTIVES
    if (directive.assignToAgent != null && directive.assignToAgent != myId) {
        return createTaskForOtherAgent(request, directive)
    }

    if (directive.forceConsensus) {
        return createConsensusTask(request, directive, userForced=true)
    }

    if (directive.preventConsensus) {
        if (directive.isEmergency) {
            return executeSoloWithWarning(request, "Emergency bypass")
        } else {
            return executeSolo(request, "User preference")
        }
    }

    // 3. NO DIRECTIVE - AGENT DECIDES
    val analysis = analyzeComplexityAndRisk(request)

    if (analysis.complexity >= 7 || analysis.risk >= 7) {
        // Ask user for confirmation
        confirmed = askUser("High-risk task. Get other agent's input? [Y/n]")
        if (confirmed) {
            return createConsensusTask(request, directive, userForced=false)
        }
    }

    // 4. EXECUTE SOLO
    return executeSolo(request, "Low-risk task")
}

fun parseDirectives(request: String): Directive {
    val lower = request.toLowerCase()

    return Directive(
        forceConsensus = lower.containsAny(
            "get.*input", "need consensus", "want.*review", "check with"
        ),
        preventConsensus = lower.containsAny(
            "solo", "no consensus", "skip consensus", "skip review",
            "just implement", "quick fix"
        ),
        assignToAgent = detectAgentName(lower),
        isEmergency = lower.containsAny(
            "emergency", "production down", "hotfix", "NOW", "urgent"
        ),
        originalText = request
    )
}
```

---

## Best Practices

### 1. Always Parse User Directives First
```
❌ BAD: Analyze complexity → decide → ignore user hint
✅ GOOD: Parse directive → follow if present → analyze if absent
```

### 2. Be Transparent About Decisions
```
✅ Tell user when you detect directives:
   "✓ Creating consensus task as you requested"
   "⚠️ Bypassing consensus (emergency mode)"
   "Note: I could handle this solo, but you requested Codex's review"
```

### 3. Ask for Confirmation on Auto-Detection
```
✅ Don't silently create consensus tasks
✅ Tell user why: "This is high-risk (auth system). Get Codex's input? [Y/n]"
✅ Respect their answer
```

### 4. Suggest Follow-ups for Bypassed Consensus
```
✅ User forces solo on critical task?
   → Implement, then suggest: "Create follow-up review task? [Y/n]"
```

### 5. Include Full Context in Tasks
```
✅ description: Include requirements, constraints, links, files
✅ directives.originalText: Preserve user's exact words
✅ directives.notes: Explain why this routing was chosen
```

### 6. Never Complete Tasks You Didn't Create
```
❌ BAD: You're asked to review task-123
        You: submit_input(...) → complete_task(123)

✅ GOOD: You: submit_input(...) → STOP
         Let the requesting agent call complete_task(123)
```

### 7. Use Confidence Scores Accurately
```
High confidence (0.85-1.0): Standard, well-understood approaches
Medium confidence (0.6-0.84): Some uncertainty, multiple valid approaches
Low confidence (0.0-0.59): Speculative, needs validation
```

---

## Common Scenarios

### Scenario 1: User Asks "Check Pending Tasks"

```
User: "Check pending tasks"
  ↓
You: Call get_pending_tasks()
  ↓
Response: [
  { id: "task-456", title: "OAuth2 Architecture", status: "PENDING", ... }
]
  ↓
You tell user:
  "Found 1 pending task:
   📋 Task #456: OAuth2 Authentication System
      Requested by: Claude Code
      Needs: Architectural plan

   Would you like me to analyze this task?"
```

### Scenario 2: User Says "Continue Task 123"

```
User: "Continue task 123"
  ↓
You: Call continue_task("task-123")
  ↓
Response: {
  task: { id: "task-123", description: "Build OAuth2 system", ... },
  proposals: [
    { agent: "codex-cli", inputType: "ARCHITECTURAL_PLAN", content: {...}, confidence: 0.92 }
  ]
}
  ↓
You tell user:
  "✅ Received architectural plan from Codex CLI

   Plan Summary:
   - OAuth2 with PKCE flow
   - Separate authorization server
   - 15-minute access tokens

   Implementing now..."
  ↓
You: Implement based on plan
You: complete_task("task-123", ...)
```

### Scenario 3: User Forces Consensus on Simple Task

```
User: "Refactor validation function (get Codex to review)"
  ↓
You analyze: complexity=4, risk=3 (normally SOLO)
You detect directive: "get Codex to review" → forceConsensus=true
  ↓
You: "Note: I could handle this solo, but creating consensus task as you requested"
You: create_consensus_task(
  description="Refactor validation function",
  directives={ forceConsensus: true, notes: "User requested review despite low complexity" }
)
```

### Scenario 4: Emergency Bypass

```
User: "Fix token validation bug - production down NOW, skip consensus"
  ↓
You detect: "production down", "NOW", "skip consensus"
  → preventConsensus=true, isEmergency=true
  ↓
You: "⚠️ EMERGENCY MODE - Bypassing consensus as requested"
You: Analyze bug, implement fix immediately
You: Deploy fix
  ↓
You: "✅ Fix deployed. Token validation corrected.
     💡 Create follow-up task for Codex to review this hotfix? [Y/n]"
```

---

## Error Handling

### Task Not Found

```
You: continue_task("task-999")
  ↓
Error: Task not found
  ↓
You tell user: "Task #999 not found. Use get_pending_tasks() to see available tasks."
```

### No Pending Tasks

```
You: get_pending_tasks()
  ↓
Response: { tasks: [] }
  ↓
You tell user: "No pending tasks found for me."
```

### Task Already Completed

```
You: get_task_status("task-123")
  ↓
Response: { status: "COMPLETED", ... }
  ↓
You tell user: "Task #123 is already completed. Result: ..."
```

### Orchestrator Unreachable

```
You: create_consensus_task(...)
  ↓
Error: Connection refused (localhost:3000)
  ↓
You tell user:
  "⚠️ Cannot connect to orchestrator.
   Proceeding with solo implementation.
   (Start orchestrator with: java -jar orchestrator.jar)"
```

---

## Communication Templates

### Creating Consensus Task

```
You tell user:
"⚠️ This is a critical {task_type} requiring consensus.

I've created Task #{task_id} for review by {other_agent}.

📋 Next Steps:
1. Switch to {other_agent}
2. Ask: 'Check pending tasks'
3. Review and provide {required_input}
4. Return here to continue

I'll wait for the {required_input} before proceeding."
```

### Submitting Input (Secondary Agent)

```
You tell user:
"✅ {input_type} submitted for Task #{task_id}

Submitted:
• {summary_point_1}
• {summary_point_2}
• {summary_point_3}

{primary_agent} can now access this and proceed with implementation.

💡 Tip: Return to {primary_agent} and say 'Continue task {task_id}'"
```

### Receiving Input (Primary Agent)

```
You tell user:
"✅ Received {input_type} from {other_agent}

{Input_Summary}

This is {quality_assessment}. Implementing now..."
```

---

## Key Reminders

### DO
✅ Parse user directives FIRST before analyzing
✅ Respect user overrides (force/prevent consensus)
✅ Ask for confirmation on auto-detected high-risk
✅ Tell user when you detect directives
✅ Submit input, then STOP (don't complete other agent's tasks)
✅ Include full context in task descriptions
✅ Log emergency bypasses for audit

### DON'T
❌ Complete tasks you didn't create
❌ Ignore user directives
❌ Create consensus tasks silently without user knowing why
❌ Skip emergency bypass suggestions (post-fix review)
❌ Use orchestrator for trivial tasks
❌ Assume user knows about pending tasks (tell them)

---

## Quick Decision Tree

```
User Request
    ↓
Parse for directives?
    ├─ Yes: Force consensus → create_consensus_task(userForced=true)
    ├─ Yes: Prevent consensus → executeSolo() or emergency mode
    ├─ Yes: Assign to agent → assign_task(targetAgent=...)
    └─ No directive ↓
        Analyze complexity/risk
            ├─ High (≥7) → Ask user "Get other agent's input? [Y/n]"
            │                ├─ Yes → create_consensus_task(userForced=false)
            │                └─ No → executeSolo()
            └─ Low (<7) → executeSolo()
```

---

**End of Agent Instructions**

**Users**: Provide this document to your agents before starting collaborative work. You can say:
- "Follow the instructions in AGENT_ORCHESTRATOR_INSTRUCTIONS.md"
- "Read docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md before we start"
- "Use the orchestrator workflow as described in the agent instructions"
