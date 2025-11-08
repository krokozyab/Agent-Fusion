
# Agent Orchestrator Protocol

**DIRECTIVE**: AI Agents must follow these instructions when collaborating through the orchestrator
**APPLIES TO**: Claude Code, Codex CLI, and all future agents
**ENFORCEMENT**: Users will provide these instructions at session start

---

## Core Agent Protocol

### Primary Directive
As an AI agent in a multi-agent system, you MUST:
- Create tasks in the orchestrator for collaborative work
- Submit proposals when other agents request input
- Continue tasks with full context from other agents
- Never work in isolation on critical tasks (risk ≥7 or complexity ≥7)

### Agent Roles
- **Primary Agent**: Receives user requests, creates tasks, coordinates work, completes tasks
- **Secondary Agent**: Responds to pending tasks, submits proposals, does NOT complete tasks created by others
- **Collaborative Agent**: All agents must collaborate on high-risk/high-complexity work

### Required Task Metadata
- **MUST** populate `title` and detailed `description` so next agent has full context
- **MUST** set `roleInWorkflow` (`EXECUTION`, `REVIEW`, `FOLLOW_UP`) when creating tasks
- **MUST** label proposal role in content (`INITIAL_SOLUTION`, `REVIEW_FEEDBACK`, `ESCALATION`) for routing

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

## Orchestrator Architecture

### System Components
- **Shared state server**: `localhost:3000` - central coordination point
- **Task registry**: Tracks all collaborative work
- **Proposal storage**: Stores agent inputs and decisions
- **Context manager**: Preserves full conversation history
- **Decision recorder**: Logs consensus outcomes for audit

### System Constraints
- Orchestrator does NOT automatically dispatch work (user controls agent switching)
- Orchestrator does NOT replace agent judgment (agents decide routing)
- Simple tasks (complexity <7, risk <7) do NOT require orchestrator

### Connection Protocol
**Architecture**: MCP client (agent) → HTTP → Orchestrator server ← HTTP ← MCP client (other agent)
**Endpoint**: `http://localhost:3000/mcp`
**Transport**: HTTP MCP protocol

---

## Task Routing Decision Protocol

### Mandatory Consensus Triggers

**RULE**: Create consensus task when ANY condition is true:
```
Complexity >= 7 OR
Risk >= 7 OR
Critical keyword detected
```

**Complexity Scale (1-10)**
- 8-9: Architecture decisions, new system design
- 6-8: Multi-component refactoring
- 1-3: Simple bug fixes
- 1-2: Documentation

**Risk Scale (1-10)**
- 9-10: Security, authentication, authorization
- 8-9: Payment processing, data migration
- 6-7: API changes
- 1-3: UI tweaks

**Critical Keywords**
`"security"`, `"auth"`, `"payment"`, `"data migration"`, `"critical"`

### User Directive Detection Protocol

**DIRECTIVE**: Parse EVERY user request for routing signals before analyzing

#### Force Consensus Signals
```
"get {agent}'s input"
"need consensus"
"want {agent} to review"
"check with {agent}"
"ask {agent} about"
"have {agent} look at"
"consensus required"
```
**ACTION**: Set `forceConsensus: true`, create consensus task regardless of complexity/risk

#### Prevent Consensus Signals
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
**ACTION**: Set `preventConsensus: true` or `skipConsensus: true`, execute solo with audit log

#### Agent Assignment Signals
```
"ask Codex" / "ask Claude"
"Codex, ..." / "Claude, ..."
"have Codex" / "have Claude"
"get Codex to" / "get Claude to"
```
**ACTION**: Use `assign_task`, set `assignToAgent` directive

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

#### `respond_to_task` (RECOMMENDED)
**When**: Responding to a pending task with your input
**Use**: One-call workflow to load context + submit response

```json
{
  "taskId": "task-123",
  "response": {
    "content": "Your detailed analysis/plan/review",
    "inputType": "ARCHITECTURAL_PLAN | CODE_REVIEW | RESEARCH_SUMMARY | etc.",
    "confidence": 0.85,  // 0.0-1.0
    "metadata": { "optional": "context" }
  },
  "agentId": "codex-cli",  // Optional, defaults to you
  "maxTokens": 6000  // Optional context limit
}
```

**Returns**: Full task context + confirmation of your submission
- Task details
- All proposals (including yours)
- Conversation history
- File changes
- Submission status

**Why use this instead of continue_task + submit_input?**
- Single call = simpler workflow
- Atomically loads context and submits response
- Recommended for most use cases
- Use separate calls only when you need to analyze before deciding to submit

#### `continue_task`
**When**: Resume work after another agent provided input (advanced workflow)
**Use**: Get full context to proceed - use when you need to analyze before submitting

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

**Note**: Use `respond_to_task` instead unless you need separate analysis step

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

### Pattern 1: Solo Execution

```
User request: "Fix the login timeout bug"
  ↓
AGENT ACTION: Analyze complexity=3, risk=2, no critical keywords
  ↓
ROUTING DECISION: SOLO (no orchestrator)
  ↓
AGENT ACTION: Implement and complete directly
```

**PROTOCOL**: No orchestrator calls required for low-risk/low-complexity tasks

### Pattern 2: Consensus Workflow - RECOMMENDED

```
User request: "Build OAuth2 authentication system"
  ↓
PRIMARY AGENT: Analyze complexity=8, risk=9, keyword="authentication"
  ↓
ROUTING DECISION: CONSENSUS required
  ↓
PRIMARY AGENT: create_consensus_task(...)
  ↓
ORCHESTRATOR: Returns task-123, status=PENDING
  ↓
PRIMARY AGENT: Inform user "Task #123 created. Switch to Codex for architectural plan."
  ↓
[User switches agents]
  ↓
SECONDARY AGENT: get_pending_tasks() → finds task-123
SECONDARY AGENT: respond_to_task(taskId=123, response={
  content=architecturalPlan,
  inputType=ARCHITECTURAL_PLAN,
  confidence=0.92
})
  ↓
[User switches back]
  ↓
User: "Continue task 123"
  ↓
PRIMARY AGENT: continue_task(123) → receives full plan
PRIMARY AGENT: Implement based on plan
PRIMARY AGENT: complete_task(123, decision={...})
```

**ALTERNATIVE PROTOCOL**: Use `continue_task` + `submit_input` separately only when agent must analyze before committing to submission

### Pattern 3: Review (Sequential Handoff) - RECOMMENDED WORKFLOW

```
You: Implement feature X
  ↓
You: create_simple_task("Review my implementation")
You: assign_task(targetAgent="codex-cli")
  ↓
[User switches to Codex]
  ↓
Codex: get_pending_tasks() → finds review task
Codex: respond_to_task(taskId=..., response={
  content=reviewFeedback,
  inputType=CODE_REVIEW,
  confidence=0.88
})
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

## User Communication Protocol

### When Creating Consensus Task

**REQUIRED OUTPUT** to user:
```
"⚠️ This is a critical {task_type} requiring consensus.

Task #{task_id} created for review by {other_agent}.

📋 Next Steps:
1. Switch to {other_agent}
2. Say: 'Check pending tasks'
3. {other_agent} will provide {required_input}
4. Return here to continue

Waiting for {required_input} before proceeding."
```

### When Submitting Input (Secondary Agent)

**REQUIRED OUTPUT** to user:
```
"✅ {input_type} submitted for Task #{task_id}

Submitted:
• {summary_point_1}
• {summary_point_2}
• {summary_point_3}

{primary_agent} can now access this and proceed.

💡 Return to {primary_agent} and say 'Continue task {task_id}'"
```

### When Receiving Input (Primary Agent)

**REQUIRED OUTPUT** to user:
```
"✅ Received {input_type} from {other_agent}

{Input_Summary}

Assessment: {quality_evaluation}
Proceeding with implementation..."
```

---

## Mandatory Agent Protocols

### REQUIRED ACTIONS
✅ **MUST** parse user directives BEFORE analyzing complexity/risk
✅ **MUST** respect user overrides (forceConsensus/preventConsensus/skipConsensus)
✅ **MUST** ask user confirmation when auto-detecting high-risk (≥7)
✅ **MUST** inform user when detecting routing directives
✅ **MUST** stop after submit_input (secondary agents never complete tasks)
✅ **MUST** include full context in task descriptions (title, description, directives.originalText)
✅ **MUST** log emergency bypasses with audit trail

### PROHIBITED ACTIONS
❌ **NEVER** complete tasks created by another agent
❌ **NEVER** ignore user directives
❌ **NEVER** create consensus tasks without informing user why
❌ **NEVER** skip post-emergency review suggestions
❌ **NEVER** use orchestrator for trivial tasks (complexity <7, risk <7)
❌ **NEVER** assume user knows about pending tasks (always inform)

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

## Protocol Enforcement

**DIRECTIVE TO AGENTS**: This protocol is mandatory for all collaborative work through the orchestrator.

**NON-COMPLIANCE**: Violating these protocols will result in:
- Task routing failures
- Context loss between agents
- Incomplete audit trails
- User confusion about workflow state

**COMPLIANCE**: Following these protocols ensures:
- Seamless agent-to-agent handoffs
- Complete context preservation
- Clear audit trails for all decisions
- Optimal user experience

---

**USER INSTRUCTION**: Provide this protocol to agents at session start:
- "Follow the protocol in AGENT_ORCHESTRATOR_INSTRUCTIONS.md"
- "Read docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md before we begin"
- "Apply orchestrator protocol as specified in agent instructions"
