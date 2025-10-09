# Conversation-Based Handoff Workflow

## Overview

The **Conversation-Based Handoff** workflow enables two AI agents (Claude Code and Codex CLI) to collaborate on complex tasks through asynchronous communication mediated by a shared orchestrator server. This document explains how the workflow operates from both the user and agent perspectives.

---

## Table of Contents

1. [Core Concept](#core-concept)
2. [Architecture](#architecture)
3. [Workflow Phases](#workflow-phases)
4. [Complete Example](#complete-example)
5. [MCP Tool Reference](#mcp-tool-reference)
6. [Agent Decision Making](#agent-decision-making)
7. [User Guidelines](#user-guidelines)
8. [Implementation Notes](#implementation-notes)

---

## Core Concept

### The Problem

**Traditional approach:** Single AI agent handles all tasks
- ❌ Not optimized for specific strengths
- ❌ Higher token costs
- ❌ Quality varies by task type

**Our approach:** Multiple specialized agents collaborate
- ✅ Each agent handles what they're best at
- ✅ Consensus on critical decisions
- ✅ Token optimization
- ✅ Higher quality outcomes

### How It Works

```
┌─────────────────────────────────────────────────────┐
│  CONVERSATION-BASED HANDOFF                         │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1. User works with PRIMARY agent (e.g., Claude)    │
│  2. Agent detects need for collaboration            │
│  3. Agent creates TASK in orchestrator              │
│  4. User MANUALLY switches to other agent           │
│  5. Other agent provides input via orchestrator     │
│  6. User switches BACK to primary agent             │
│  7. Primary agent continues with full context       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**Key principle:** The orchestrator is a **shared state server**, not an automated dispatcher. Agents communicate asynchronously through it, but the user controls when to switch between agents.

---

## Architecture

### System Components

```
┌──────────────────────────────────────────────────────┐
│                                                      │
│              ORCHESTRATOR (HTTP MCP Server)          │
│              Running on localhost:3000               │
│                                                      │
│  Components:                                         │
│  • Task Registry (DuckDB)                            │
│  • Proposal Storage                                  │
│  • Context Management                                │
│  • Decision Recording                                │
│  • Metrics Tracking                                  │
│                                                      │
└────────────┬──────────────────────┬──────────────────┘
             │                      │
        HTTP │                      │ HTTP
             │                      │
             ↓                      ↓
    ┌────────────────┐    ┌────────────────┐
    │  CLAUDE CODE   │    │   CODEX CLI    │
    │  (MCP Client)  │    │  (MCP Client)  │
    │                │    │                │
    │  Strengths:    │    │  Strengths:    │
    │  • Implement   │    │  • Architecture│
    │  • Refactor    │    │  • Reasoning   │
    │  • Test        │    │  • Planning    │
    └────────────────┘    └────────────────┘
```

### Communication Flow

```
Agent → Orchestrator: "Create consensus task"
Orchestrator → Database: Store task, status=WAITING
Orchestrator → Agent: "Task #123 created"

[User switches terminals]

Other Agent → Orchestrator: "Get pending tasks"
Orchestrator → Database: Query tasks for this agent
Orchestrator → Other Agent: "Task #123 needs input"

Other Agent → Orchestrator: "Submit proposal for #123"
Orchestrator → Database: Store proposal, update status
Orchestrator → Other Agent: "Proposal submitted"

[User switches back]

Agent → Orchestrator: "Continue task #123"
Orchestrator → Database: Fetch task + proposals
Orchestrator → Agent: "Here's the full context"
```

---

## Workflow Phases

### Phase 1: Task Classification

**When:** Agent receives user request

**Agent analyzes:**
- Complexity (1-10 scale)
- Risk level (1-10 scale)
- Required capabilities
- Estimated tokens

**Decision tree:**
```
IF complexity <= 3 AND risk <= 3:
    → SOLO strategy (handle alone)
ELSE IF complexity >= 7 OR risk >= 7:
    → CONSENSUS strategy (need other agent)
ELSE IF task is architectural:
    → SEQUENTIAL strategy (plan then implement)
ELSE:
    → SOLO with offer to get input
```

### Phase 2: Task Creation

**For consensus tasks:**

Agent calls MCP tool:
```json
{
  "tool": "create_consensus_task",
  "arguments": {
    "description": "Build OAuth2 authentication system",
    "complexity": 8,
    "riskLevel": 9,
    "taskType": "IMPLEMENTATION",
    "requestingAgent": "claude-code",
    "context": {
      "requirements": "...",
      "constraints": "..."
    }
  }
}
```

Orchestrator responds:
```json
{
  "taskId": "123",
  "status": "WAITING_FOR_CODEX",
  "assignedAgents": ["claude-code", "codex-cli"],
  "strategy": "CONSENSUS"
}
```

Agent informs user:
```
⚠️  This is a critical task requiring consensus.

I've created Task #123 for architectural review by Codex CLI.

Please switch to Codex CLI to provide:
- System architecture design
- Security considerations
- Implementation approach

I'll wait for the architectural plan before proceeding.
```

### Phase 3: Context Switch (User Action)

**User switches terminals/windows:**
```
FROM: Claude Code (Terminal 2)
TO:   Codex CLI (Terminal 3)
```

**User prompts other agent:**
```
User: "Check pending tasks"
```

or more directly:
```
User: "What tasks need my input?"
```

### Phase 4: Input Provision

**Codex CLI discovers task:**

Calls MCP tool:
```json
{
  "tool": "get_pending_tasks",
  "arguments": {
    "agentId": "codex-cli"
  }
}
```

Receives response:
```json
{
  "tasks": [
    {
      "id": "123",
      "description": "Build OAuth2 authentication system",
      "requestingAgent": "claude-code",
      "status": "WAITING_FOR_CODEX",
      "requiredInput": "architectural_plan",
      "context": { ... }
    }
  ]
}
```

**Codex CLI shows user:**
```
Found 1 pending task:

📋 Task #123: OAuth2 Authentication System
   Requested by: Claude Code
   Needs: Architectural plan
   
   Context:
   - Critical security component
   - API authentication
   - Production-ready requirement
   
Would you like me to analyze this task?
```

**User responds:** "Yes, analyze and provide architectural plan"

**Codex CLI works:**
- Analyzes requirements
- Designs architecture
- Creates detailed plan
- Identifies security concerns

**Codex CLI submits:**

Calls MCP tool:
```json
{
  "tool": "submit_input",
  "arguments": {
    "taskId": "123",
    "agentId": "codex-cli",
    "inputType": "architectural_plan",
    "content": {
      "architecture": {
        "components": [
          "Authorization Server",
          "Resource Server",
          "Token Validation Middleware"
        ],
        "flow": "OAuth2 with PKCE"
      },
      "security": {
        "tokenLifetime": "15 minutes",
        "refreshRotation": true,
        "rateLimit": "100/hour"
      },
      "schema": { ... },
      "recommendations": [ ... ]
    },
    "confidence": 0.92,
    "reasoning": "OAuth2 with PKCE provides secure public client authentication..."
  }
}
```

**Codex CLI confirms:**
```
✅ Architectural plan submitted for Task #123

Claude Code can now access this plan and proceed with implementation.

Plan includes:
- Complete OAuth2 architecture
- Security best practices (PKCE, short-lived tokens)
- Database schema
- Token flow diagrams
```

### Phase 5: Return to Primary Agent

**User switches back:**
```
FROM: Codex CLI (Terminal 3)
TO:   Claude Code (Terminal 2)
```

**User prompts:**
```
User: "Continue with task 123"
```

or simply:
```
User: "Continue implementing the auth system"
```

**Claude Code calls orchestrator:**

```json
{
  "tool": "continue_task",
  "arguments": {
    "taskId": "123",
    "agentId": "claude-code"
  }
}
```

**Orchestrator returns full context:**
```json
{
  "task": {
    "id": "123",
    "description": "Build OAuth2 authentication system",
    "status": "READY_FOR_IMPLEMENTATION"
  },
  "proposals": [
    {
      "agent": "codex-cli",
      "type": "architectural_plan",
      "content": { ... },
      "confidence": 0.92,
      "reasoning": "..."
    }
  ],
  "conversationHistory": [ ... ],
  "files": [ ... ]
}
```

### Phase 6: Implementation

**Claude Code proceeds:**
```
✅ Received architectural plan from Codex CLI

Plan Summary:
- OAuth2 with PKCE flow
- Separate authorization server
- 15-minute access tokens
- Refresh token rotation

This is an excellent architecture. Implementing now...

[Creates files, writes code, runs tests]

Implementation complete! All tests passing.
```

**Claude Code updates orchestrator:**
```json
{
  "tool": "complete_task",
  "arguments": {
    "taskId": "123",
    "agentId": "claude-code",
    "result": {
      "status": "COMPLETED",
      "filesCreated": [
        "auth-server/routes/token.js",
        "auth-server/middleware/validate.js",
        "database/migrations/001_oauth.sql"
      ],
      "testsPass": true
    },
    "tokensUsed": 12500
  }
}
```

---

## Complete Example

### Scenario: Building OAuth2 Authentication

**Initial State:**
- Terminal 1: Orchestrator running (background)
- Terminal 2: Claude Code (active)
- Terminal 3: Codex CLI (idle)

---

#### Step 1: User Request

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 2: Claude Code                            │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: Build an OAuth2 authentication system for     │
│       our API. This is critical - must be secure    │
│       and follow best practices.                    │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 2: Claude Analyzes & Creates Task

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 2: Claude Code (Internal Processing)      │
├─────────────────────────────────────────────────────┤
│                                                     │
│  [Analyzing request...]                             │
│  - Keywords: OAuth2, authentication, critical       │
│  - Complexity estimate: 8/10                        │
│  - Risk estimate: 9/10 (security-critical)          │
│  - Decision: Requires CONSENSUS                     │
│                                                     │
│  [Calling orchestrator.create_consensus_task()]     │
│                                                     │
└─────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 2: Claude Code (User-Facing)              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Claude: ⚠️  This is a critical authentication      │
│          system with high security requirements.    │
│                                                     │
│          I recommend getting architectural input    │
│          from Codex CLI before implementation.      │
│                                                     │
│          I've created Task #123 for consensus.      │
│                                                     │
│          📋 Next Steps:                             │
│          1. Switch to Codex CLI                     │
│          2. Ask: "Check pending tasks"              │
│          3. Review and approve architectural plan   │
│          4. Return here to continue                 │
│                                                     │
│          I'll wait for the architectural review     │
│          before proceeding with implementation.     │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 3: Switch to Codex

```
[User clicks on Terminal 3]
```

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 3: Codex CLI                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: Check pending tasks                           │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 4: Codex Discovers Task

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 3: Codex CLI                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Codex: [Calling orchestrator.get_pending_tasks()]  │
│                                                     │
│         Found 1 task waiting for input:             │
│                                                     │
│         📋 Task #123                                │
│         Title: OAuth2 Authentication System         │
│         Requested by: Claude Code                   │
│         Priority: HIGH (Risk: 9/10)                 │
│                                                     │
│         Requirements:                               │
│         • Secure OAuth2 implementation              │
│         • API authentication                        │
│         • Production-ready                          │
│         • Must follow best practices                │
│                                                     │
│         Needs from me:                              │
│         • System architecture design                │
│         • Security considerations                   │
│         • Implementation recommendations            │
│                                                     │
│         Would you like me to:                       │
│         a) Analyze and provide detailed plan        │
│         b) Show full task context first             │
│         c) Skip this task                           │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 5: User Requests Analysis

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 3: Codex CLI                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: Analyze and provide a detailed architectural  │
│       plan for task 123                             │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 6: Codex Analyzes

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 3: Codex CLI                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Codex: Analyzing OAuth2 authentication system      │
│         requirements...                             │
│                                                     │
│         [Thinking about security implications...]   │
│         [Designing token flow...]                   │
│         [Considering edge cases...]                 │
│                                                     │
│         ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                     │
│         # OAuth2 Architecture Plan                  │
│                                                     │
│         ## System Overview                          │
│                                                     │
│         I recommend a standard OAuth2 architecture  │
│         with PKCE (Proof Key for Code Exchange)     │
│         for enhanced security:                      │
│                                                     │
│         **Components:**                             │
│         1. Authorization Server (separate service)  │
│            - Token endpoint                         │
│            - Authorization endpoint                 │
│            - Token introspection                    │
│                                                     │
│         2. Resource Server (your API)               │
│            - Token validation middleware            │
│            - Protected endpoints                    │
│                                                     │
│         3. Client Applications                      │
│            - Public clients (SPA, mobile)           │
│            - Confidential clients (server-to-server)│
│                                                     │
│         ## Security Considerations                  │
│                                                     │
│         **Critical Requirements:**                  │
│         • PKCE flow for public clients              │
│         • Short-lived access tokens (15 minutes)    │
│         • Refresh token rotation                    │
│         • Rate limiting on token endpoint           │
│         • HTTPS only (enforce in production)        │
│         • Secure token storage (httpOnly cookies)   │
│                                                     │
│         **Token Design:**                           │
│         • Access tokens: JWT with short expiry      │
│         • Refresh tokens: Opaque, single-use        │
│         • Token families for rotation tracking      │
│                                                     │
│         ## Database Schema                          │
│                                                     │
│         ```sql                                      │
│         CREATE TABLE oauth_clients (                │
│           id UUID PRIMARY KEY,                      │
│           client_id VARCHAR(255) UNIQUE,            │
│           client_secret_hash VARCHAR(255),          │
│           redirect_uris TEXT[],                     │
│           grant_types TEXT[],                       │
│           created_at TIMESTAMP                      │
│         );                                          │
│                                                     │
│         CREATE TABLE oauth_tokens (                 │
│           id UUID PRIMARY KEY,                      │
│           client_id UUID REFERENCES oauth_clients,  │
│           user_id UUID,                             │
│           access_token_hash VARCHAR(255),           │
│           refresh_token_hash VARCHAR(255),          │
│           expires_at TIMESTAMP,                     │
│           scope TEXT[],                             │
│           created_at TIMESTAMP                      │
│         );                                          │
│                                                     │
│         CREATE TABLE oauth_authorization_codes (    │
│           code_hash VARCHAR(255) PRIMARY KEY,       │
│           client_id UUID,                           │
│           user_id UUID,                             │
│           redirect_uri TEXT,                        │
│           code_challenge VARCHAR(255),              │
│           expires_at TIMESTAMP                      │
│         );                                          │
│         ```                                         │
│                                                     │
│         ## Token Flow (PKCE)                        │
│                                                     │
│         ```                                         │
│         Client                     Auth Server      │
│           |                             |           │
│           | 1. Generate code_verifier   |           │
│           | 2. Hash to code_challenge   |           │
│           |                             |           │
│           | 3. /authorize + challenge   |           │
│           |---------------------------->|           │
│           |                             |           │
│           | 4. User authenticates       |           │
│           |                             |           │
│           | 5. Authorization code       |           │
│           |<----------------------------|           │
│           |                             |           │
│           | 6. /token + code_verifier   |           │
│           |---------------------------->|           │
│           |                             |           │
│           | 7. Validate verifier        |           │
│           |                             |           │
│           | 8. Access + Refresh tokens  |           │
│           |<----------------------------|           │
│         ```                                         │
│                                                     │
│         ## Implementation Recommendations           │
│                                                     │
│         1. **Use proven libraries:**                │
│            - Don't implement crypto yourself        │
│            - Use: node-oauth2-server, passport-oauth│
│                                                     │
│         2. **Token validation:**                    │
│            - JWT signature verification             │
│            - Expiry check                           │
│            - Scope validation                       │
│            - Revocation check (optional)            │
│                                                     │
│         3. **Rate limiting:**                       │
│            - Token endpoint: 10 requests/min/IP     │
│            - Authorization: 30 requests/min/user    │
│                                                     │
│         4. **Monitoring:**                          │
│            - Failed auth attempts                   │
│            - Token usage patterns                   │
│            - Anomaly detection                      │
│                                                     │
│         ## Testing Checklist                        │
│                                                     │
│         - [ ] PKCE flow validation                  │
│         - [ ] Token expiry enforcement              │
│         - [ ] Refresh token rotation                │
│         - [ ] Invalid token rejection               │
│         - [ ] Rate limit enforcement                │
│         - [ ] SQL injection prevention              │
│         - [ ] XSS prevention in redirects           │
│                                                     │
│         ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                     │
│         **Confidence:** 92%                         │
│         **Reasoning:** Standard OAuth2 with PKCE is │
│         battle-tested and addresses the security    │
│         requirements. The architecture separates    │
│         concerns properly and follows best practices│
│         from RFC 6749 and RFC 7636.                 │
│                                                     │
│         Should I submit this plan to Task #123?     │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 7: User Approves & Codex Submits

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 3: Codex CLI                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: Yes, submit the plan                          │
│                                                     │
└─────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 3: Codex CLI                              │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Codex: [Calling orchestrator.submit_input()]       │
│                                                     │
│         ✅ Architectural plan submitted for Task    │
│         #123                                        │
│                                                     │
│         Submitted:                                  │
│         • Complete system architecture              │
│         • Security best practices (PKCE, rotation)  │
│         • Database schema (3 tables)                │
│         • Token flow diagrams                       │
│         • Implementation recommendations            │
│         • Testing checklist                         │
│                                                     │
│         Claude Code can now access this plan and    │
│         proceed with implementation.                │
│                                                     │
│         💡 Tip: You can return to Claude Code now   │
│         and continue with: "Continue task 123"      │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 8: Switch Back to Claude

```
[User clicks on Terminal 2]
```

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 2: Claude Code                            │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: Continue with task 123                        │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

#### Step 9: Claude Retrieves Plan & Implements

```
┌─────────────────────────────────────────────────────┐
│  TERMINAL 2: Claude Code                            │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Claude: [Calling orchestrator.continue_task()]     │
│                                                     │
│          ✅ Received architectural plan from Codex  │
│          CLI!                                       │
│                                                     │
│          Plan Summary:                              │
│          • OAuth2 with PKCE flow                    │
│          • Separate authorization server            │
│          • 15-minute access tokens                  │
│          • Refresh token rotation for security      │
│          • Comprehensive database schema            │
│                                                     │
│          This is an excellent, secure architecture. │
│          I'll implement it now following all the    │
│          recommendations.                           │
│                                                     │
│          Starting implementation...                 │
│                                                     │
│          [Creating directory structure...]          │
│          ✓ auth-server/                             │
│          ✓ api-server/                              │
│          ✓ database/                                │
│          ✓ tests/                                   │
│                                                     │
│          [Implementing authorization server...]     │
│          ✓ routes/authorize.js                      │
│          ✓ routes/token.js                          │
│          ✓ middleware/pkce.js                       │
│          ✓ middleware/validate.js                   │
│          ✓ models/Client.js                         │
│          ✓ models/Token.js                          │
│                                                     │
│          [Implementing resource server...]          │
│          ✓ middleware/auth.js (JWT validation)      │
│          ✓ middleware/scope.js (permission check)   │
│                                                     │
│          [Creating database migrations...]          │
│          ✓ migrations/001_create_oauth_tables.sql   │
│          ✓ migrations/002_add_indexes.sql           │
│                                                     │
│          [Implementing tests...]                    │
│          ✓ tests/authorize.test.js                  │
│          ✓ tests/token.test.js                      │
│          ✓ tests/pkce.test.js                       │
│          ✓ tests/validation.test.js                 │
│                                                     │
│          [Running tests...]                         │
│          ✓ All 24 tests passing                     │
│                                                     │
│          [Security validation...]                   │
│          ✓ PKCE flow implemented correctly          │
│          ✓ Token expiry enforced                    │
│          ✓ Refresh rotation working                 │
│          ✓ Rate limiting configured                 │
│          ✓ Input validation on all endpoints        │
│                                                     │
│          ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│                                                     │
│          ✅ Implementation complete!                │
│                                                     │
│          Summary:                                   │
│          • OAuth2 server with PKCE support          │
│          • 3 database tables with proper indexes    │
│          • Secure token validation middleware       │
│          • Comprehensive test suite (100% coverage) │
│          • Rate limiting configured                 │
│          • All security recommendations followed    │
│                                                     │
│          The system follows Codex's architectural   │
│          plan exactly, with all recommended         │
│          security features implemented.             │
│                                                     │
│          Files created: 18                          │
│          Tests: 24 passing                          │
│          Token usage: ~12,500 tokens                │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## MCP Tool Reference

### For Primary Agent (Creates Tasks)

#### `create_consensus_task`

**Purpose:** Create a task requiring input from another agent

**Parameters:**
```json
{
  "description": "string",           // Task description
  "complexity": "number",             // 1-10 (optional, auto-detected)
  "riskLevel": "number",              // 1-10 (optional, auto-detected)
  "taskType": "string",               // IMPLEMENTATION, ARCHITECTURE, etc.
  "requestingAgent": "string",        // Agent creating task
  "forceConsensus": "boolean",        // User explicitly requested consensus
  "userDirective": "string",          // Original user directive text
  "context": {                        // Optional context
    "requirements": "string",
    "constraints": "string",
    "files": ["string"]
  }
}
```

**Returns:**
```json
{
  "taskId": "string",
  "status": "WAITING_FOR_{AGENT}",
  "assignedAgents": ["string"],
  "strategy": "CONSENSUS",
  "estimatedTokens": "number",
  "userOverride": "boolean"          // True if user forced this
}
```

**Usage Examples:**

```javascript
// Agent auto-detected consensus needed
create_consensus_task({
  description: "Build OAuth2 authentication",
  complexity: 8,
  riskLevel: 9,
  requestingAgent: "claude-code"
})

// User explicitly requested consensus
create_consensus_task({
  description: "Refactor auth module (get Codex's review)",
  forceConsensus: true,
  userDirective: "get Codex's review",
  requestingAgent: "claude-code"
})

// User assigned to specific agent
create_consensus_task({
  description: "Design database schema",
  targetAgent: "codex-cli",
  userDirective: "Ask Codex to design",
  requestingAgent: "claude-code"
})
```

#### `create_simple_task`

**Purpose:** Create a task for solo execution (no consensus needed)

**Parameters:**
```json
{
  "description": "string",
  "taskType": "string",
  "skipConsensus": "boolean",        // User forced solo execution
  "userDirective": "string",         // Original user directive
  "context": {}
}
```

**Returns:**
```json
{
  "taskId": "string",
  "status": "IN_PROGRESS",
  "assignedAgent": "string",
  "consensusSkipped": "boolean",     // True if user bypassed
  "skipReason": "string"             // Why consensus was skipped
}
```

**Usage Examples:**

```javascript
// Normal simple task
create_simple_task({
  description: "Fix typo in README",
  taskType: "DOCUMENTATION"
})

// User forced solo on critical task
create_simple_task({
  description: "Fix auth bug NOW - production down",
  skipConsensus: true,
  userDirective: "production down - skip consensus",
  taskType: "HOTFIX"
})
```

#### `assign_task`

**Purpose:** Create task assigned to specific agent (user-directed)

**Parameters:**
```json
{
  "description": "string",
  "targetAgent": "string",           // "codex-cli", "claude-code"
  "taskType": "string",
  "priority": "string",              // LOW, MEDIUM, HIGH, CRITICAL
  "waitForCompletion": "boolean",    // Block until done
  "context": {}
}
```

**Returns:**
```json
{
  "taskId": "string",
  "assignedTo": "string",
  "status": "WAITING_FOR_{AGENT}",
  "estimatedTime": "number"
}
```

**Usage Example:**

```javascript
assign_task({
  description: "Design database schema for multi-tenant system",
  targetAgent: "codex-cli",
  taskType: "ARCHITECTURE",
  priority: "HIGH",
  context: {
    requirements: "Support 1000+ tenants, data isolation required"
  }
})
```

#### `continue_task`

**Purpose:** Resume work on a task after other agent provided input

**Parameters:**
```json
{
  "taskId": "string",
  "agentId": "string"
}
```

**Returns:**
```json
{
  "task": {
    "id": "string",
    "description": "string",
    "status": "string"
  },
  "proposals": [
    {
      "agent": "string",
      "type": "string",
      "content": {},
      "confidence": "number",
      "reasoning": "string"
    }
  ],
  "conversationHistory": [],
  "files": []
}
```

#### `complete_task`

**Purpose:** Mark task as completed

**Parameters:**
```json
{
  "taskId": "string",
  "agentId": "string",
  "result": {
    "status": "COMPLETED",
    "filesCreated": ["string"],
    "testsPass": "boolean",
    "summary": "string"
  },
  "tokensUsed": "number"
}
```

### For Secondary Agent (Provides Input)

#### `get_pending_tasks`

**Purpose:** Retrieve tasks waiting for this agent's input

**Parameters:**
```json
{
  "agentId": "string (optional)"
}
```

**Returns:**
```json
{
  "tasks": [
    {
      "id": "string",
      "description": "string",
      "requestingAgent": "string",
      "status": "string",
      "requiredInput": "string",
      "context": {},
      "priority": "string",
      "createdAt": "timestamp"
    }
  ]
}
```

#### `get_task_status`

**Purpose:** Check status of a specific task

**Parameters:**
```json
{
  "taskId": "string"
}
```

**Returns:**
```json
{
  "id": "string",
  "status": "string",
  "assignedAgents": ["string"],
  "proposals": [],
  "updatedAt": "timestamp"
}
```

#### `submit_input`

**Purpose:** Submit analysis/plan/review for a task

**Parameters:**
```json
{
  "taskId": "string",
  "agentId": "string",
  "inputType": "string",  // architectural_plan, code_review, etc.
  "content": {},          // Structured input
  "confidence": "number", // 0.0-1.0
  "reasoning": "string"   // Why this approach
}
```

**Returns:**
```json
{
  "proposalId": "string",
  "taskId": "string",
  "status": "SUBMITTED",
  "nextStatus": "READY_FOR_{AGENT}"
}
```

---

## Agent Decision Making

### User Control Mechanisms

**Users have THREE levels of control over routing:**

#### Level 1: Implicit (Let Agent Decide)

User provides no routing directive - agent analyzes and decides:

```
User: "Build OAuth2 authentication"

Agent analyzes:
  - Complexity: 8/10
  - Risk: 9/10
  - Keywords: "authentication" (critical)
  
Agent decides: CONSENSUS needed
Agent asks: "This is high-risk. Get Codex's input? [Y/n]"
```

#### Level 2: Natural Language Directives

User includes hints in natural language - agent detects and follows:

```
┌─────────────────────────────────────────────────────┐
│  FORCE CONSENSUS                                    │
├─────────────────────────────────────────────────────┤
│                                                     │
│  User: "Build auth system (get Codex's input)"      │
│  User: "Refactor this - need consensus"             │
│  User: "Check with Codex before implementing"       │
│  User: "Want Codex to review this approach"         │
│                                                     │
│  → Agent creates consensus task immediately         │
│                                                     │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  PREVENT CONSENSUS                                  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  User: "Build auth system (solo, I'll handle)"      │
│  User: "Just implement - no consensus needed"       │
│  User: "Skip review, production is down!"           │
│  User: "Quick fix only, no time for consensus"      │
│                                                     │
│  → Agent handles solo, bypasses consensus           │
│                                                     │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  ASSIGN TO SPECIFIC AGENT                           │
├─────────────────────────────────────────────────────┤
│                                                     │
│  User: "Ask Codex to design the database schema"    │
│  User: "Have Codex review my implementation"        │
│  User: "Codex, can you plan the architecture?"      │
│  User: "Need Codex's opinion on this approach"      │
│                                                     │
│  → Creates task assigned to specified agent         │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Level 3: Explicit MCP Tools (Future)

Direct tool calls with explicit parameters:

```json
{
  "tool": "assign_task",
  "arguments": {
    "description": "Design database schema",
    "targetAgent": "codex-cli",
    "forceConsensus": true,
    "priority": "HIGH"
  }
}
```

### Directive Keywords Reference

**Agents detect these keywords to determine user intent:**

```yaml
Force Consensus:
  - "get {agent}'s input"
  - "need consensus"
  - "want {agent} to review"
  - "check with {agent}"
  - "ask {agent} about"
  - "have {agent} look at"
  - "need {agent}'s opinion"
  - "consensus required"
  
Prevent Consensus:
  - "solo"
  - "no consensus"
  - "skip consensus"
  - "skip review"
  - "just implement"
  - "quick fix"
  - "emergency"
  - "production down"
  - "hotfix"
  - "I'll handle this"
  
Assign to Codex:
  - "ask Codex"
  - "Codex, ..."
  - "have Codex"
  - "get Codex to"
  - "Codex should"
  - "need Codex"
  
Assign to Claude:
  - "ask Claude"
  - "Claude, ..."
  - "have Claude"
  - "get Claude to"
  - "Claude should"
  - "need Claude"
```

### When to Create Consensus Tasks

**Automatic triggers (when no user directive):**
```
Complexity >= 7  OR
Risk >= 7  OR
Critical keywords: "security", "auth", "payment", "data migration" OR
User directive: Force consensus keywords detected
```

**Agent reasoning process (pseudocode for illustration):**
```
function shouldUseConsensus(request):
    // 1. Check for explicit user directives first
    directive = parseUserDirective(request)
    
    if directive.forceConsensus:
        return true, "User requested consensus"
    
    if directive.preventConsensus:
        return false, "User bypassed consensus"
    
    if directive.assignToOtherAgent:
        return true, "User assigned to specific agent"
    
    // 2. No directive - agent analyzes automatically
    complexity = estimateComplexity(request)
    risk = estimateRisk(request)
    
    if complexity >= 7 OR risk >= 7:
        // Ask user for confirmation
        confirmed = askUser("High-risk task. Get other agent's input? [Y/n]")
        return confirmed, "High complexity/risk"
    
    // 3. Check for critical keywords
    criticalKeywords = ["critical", "security", "auth", "payment"]
    if containsAny(request, criticalKeywords):
        confirmed = askUser("Critical system. Get consensus? [Y/n]")
        return confirmed, "Critical component detected"
    
    // 4. Default to solo
    return false, "Standard task - handle solo"
```

**Note:** The pseudocode above is for illustration. Actual implementation is in Kotlin.

### What to Include in Proposals

**For architectural plans:**
- System component diagram
- Data flow
- Security considerations
- Technology choices with rationale
- Database schema
- API contracts
- Testing strategy

**For code reviews:**
- Issues found (by severity)
- Security vulnerabilities
- Performance concerns
- Best practice violations
- Suggested improvements
- Overall assessment

**For implementation reviews:**
- Correctness validation
- Edge case coverage
- Error handling assessment
- Test coverage analysis
- Documentation completeness

---

## User Guidelines

## User Guidelines

### User Control in Practice

#### Example 1: User Forces Consensus (Agent Would Skip It)

```
┌─────────────────────────────────────────────────────┐
│  SCENARIO: Simple task, but user wants review       │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: "Refactor the user validation function.      │
│        Get Codex to review before merging."         │
│                                                     │
│  Claude: [Analyzes: complexity=4, risk=3]           │
│          [Detects directive: "get Codex to review"] │
│                                                     │
│          ✓ Creating consensus task as requested     │
│          (Note: I could handle this solo, but       │
│          you asked for Codex's review)              │
│                                                     │
│          Task #128 created, waiting for Codex input │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Example 2: User Prevents Consensus (Agent Recommends It)

```
┌─────────────────────────────────────────────────────┐
│  SCENARIO: Critical task, emergency bypass          │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: "Fix the auth token validation bug NOW.       │
│        Production is down. Skip consensus."         │
│                                                     │
│  Claude: [Analyzes: complexity=7, risk=9]           │
│          [Detects directive: "skip consensus"]      │
│          [Detects: "production down" = emergency]   │
│                                                     │
│          ⚠️  EMERGENCY MODE                         │
│          Bypassing consensus as requested           │
│          (Recommended: Create follow-up review task)│
│                                                     │
│          [Analyzing bug...]                         │
│          Found: Token expiry check missing          │
│          [Applying hotfix...]                       │
│          ✓ Fixed and deployed                       │
│                                                     │
│          💡 Create Task #129 for post-fix review?   │
│          This should be reviewed by Codex when      │
│          the emergency is over. [Y/n]               │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Example 3: User Assigns to Specific Agent

```
┌─────────────────────────────────────────────────────┐
│  SCENARIO: User knows which agent is better         │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: "Ask Codex to design the database schema      │
│        for the multi-tenant architecture."          │
│                                                     │
│  Claude: [Detects: "Ask Codex" = assign to Codex]   │
│                                                     │
│          Creating task assigned to Codex CLI...     │
│          Task #130 created                          │
│                                                     │
│          This task is now waiting for Codex.        │
│          Switch to Codex CLI to work on it.         │
│                                                     │
│          (I could help with implementation after    │
│          Codex provides the schema design)          │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Example 4: Agent Suggests, User Confirms

```
┌─────────────────────────────────────────────────────┐
│  SCENARIO: Agent detects risk, asks user            │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: "Implement payment processing with Stripe"    │
│                                                     │
│  Claude: [Analyzes: complexity=8, risk=10]          │
│          [Keywords: "payment" = critical]           │
│                                                     │
│          ⚠️  High-Risk Task Detected                │
│                                                     │
│          This is a payment processing system with   │
│          financial implications. I strongly         │
│          recommend getting Codex's architectural    │
│          review for:                                │
│          • Security best practices                  │
│          • Error handling strategy                  │
│          • PCI compliance considerations            │
│                                                     │
│          Create consensus task? [Y/n]               │
│                                                     │
│  You: Y                                             │
│                                                     │
│  Claude: ✓ Task #131 created                        │
│          Waiting for Codex's security review        │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Example 5: User Overrides Agent's Suggestion

```
┌─────────────────────────────────────────────────────┐
│  SCENARIO: Agent suggests consensus, user declines  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: "Add rate limiting to the API endpoints"      │
│                                                     │
│  Claude: [Analyzes: complexity=6, risk=7]           │
│                                                     │
│          This affects API security. Get Codex's     │
│          input on rate limiting strategy? [Y/n]     │
│                                                     │
│  You: n - I already have a design in mind           │
│                                                     │
│  Claude: Understood. Proceeding with implementation │
│                                                     │
│          [Implementing rate limiting...]            │
│          • Using token bucket algorithm             │
│          • Redis for distributed counting           │
│          • Configurable limits per endpoint         │
│                                                     │
│          ✓ Implementation complete                  │
│                                                     │
│          💡 You can ask Codex to review this later  │
│          if you'd like a second opinion.            │
│                                                     │
└─────────────────────────────────────────────────────┘
```

#### Example 6: Mid-Task Directive Change

```
┌─────────────────────────────────────────────────────┐
│  SCENARIO: User changes mind mid-implementation     │
├─────────────────────────────────────────────────────┤
│                                                     │
│  You: "Build a user registration system"            │
│                                                     │
│  Claude: [Starting implementation...]               │
│          Creating user model...                     │
│          Creating registration endpoint...          │
│                                                     │
│  You: "Wait - actually, get Codex to review the     │
│        security approach before we go further"      │
│                                                     │
│  Claude: [Detects: directive change]                │
│                                                     │
│          ✓ Pausing implementation                   │
│          Creating consensus task for security review│
│                                                     │
│          Task #132 created with current progress:   │
│          • User model (complete)                    │
│          • Registration endpoint (partial)          │
│                                                     │
│          Codex can review what's done so far and    │
│          provide security recommendations.          │
│                                                     │
│          Switch to Codex when ready.                │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Terminal Management

**Recommended setup:**
```
Terminal 1: Orchestrator (background)
  $ java -jar orchestrator.jar
  [INFO] Server started on port 3000
  
Terminal 2: Primary agent (Claude Code)
  $ claude
  
Terminal 3: Secondary agent (Codex CLI)
  $ codex
```

**Or use tmux/screen:**
```bash
# Create session with 3 panes
tmux new-session \; \
  split-window -h \; \
  split-window -v \; \
  select-pane -t 0
  
# Pane 0: Orchestrator
# Pane 1: Claude Code
# Pane 2: Codex CLI
```

### Workflow Best Practices

**1. Start with your primary agent**
- Choose based on task type
- Architecture/Planning → Codex CLI
- Implementation/Refactoring → Claude Code

**2. Let agents guide you**
- They'll tell you when to switch
- Task IDs make it easy to resume

**3. Context is preserved**
- Everything stored in orchestrator
- Can leave and come back hours later
- Full history available

**4. You control the pace**
- Async workflow
- No rush to switch immediately
- Can review proposals before continuing

### Common Patterns

**Pattern 1: Planning → Implementation**
```
1. Start in Codex CLI
2. "Design architecture for X"
3. Codex creates plan, submits to orchestrator
4. Switch to Claude Code
5. "Implement the architecture"
6. Claude retrieves plan, implements
```

**Pattern 2: Implementation → Review**
```
1. Start in Claude Code
2. "Build feature X"
3. Claude detects: complex, requests review
4. Switch to Codex CLI
5. Codex reviews, provides feedback
6. Switch back to Claude
7. Claude refines based on feedback
```

**Pattern 3: Consensus Decision**
```
1. Start in either agent
2. Agent: "This needs consensus"
3. Both agents analyze independently
4. You switch between them
5. Orchestrator merges best approach
6. Return to primary to implement
```

---

## Implementation Notes

### For Orchestrator Developers

**Task State Machine:**
```
CREATED → WAITING_FOR_{AGENT} → READY_FOR_{AGENT} → IN_PROGRESS → COMPLETED
                                                                  → FAILED
```

**Database Schema:**
```sql
CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    description TEXT,
    complexity INTEGER,
    risk_level INTEGER,
    status TEXT,
    assigned_agents TEXT[],
    requesting_agent TEXT,
    routing_strategy TEXT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE proposals (
    id TEXT PRIMARY KEY,
    task_id TEXT REFERENCES tasks(id),
    agent_id TEXT,
    input_type TEXT,
    content JSON,
    confidence DOUBLE,
    reasoning TEXT,
    created_at TIMESTAMP
);
```

**Concurrency Handling:**
```kotlin
// Use optimistic locking for task updates
suspend fun updateTaskStatus(taskId: String, newStatus: String) {
    Database.transaction {
        val task = taskRepo.findById(taskId) ?: throw TaskNotFound()
        
        // Validate state transition
        if (!isValidTransition(task.status, newStatus)) {
            throw InvalidStateTransition()
        }
        
        // Update with version check
        taskRepo.updateWithVersionCheck(
            taskId = taskId,
            newStatus = newStatus,
            expectedVersion = task.version
        )
    }
}
```

### For Agent Developers

**Parsing User Directives:**

```kotlin
data class UserDirective(
    val forceConsensus: Boolean = false,
    val preventConsensus: Boolean = false,
    val assignToAgent: String? = null,
    val isEmergency: Boolean = false,
    val originalText: String = ""
)

fun parseUserDirective(request: String): UserDirective {
    val lowerRequest = request.toLowerCase()
    
    // Check for agent assignment
    val assignToAgent = when {
        lowerRequest.contains(Regex("ask codex|codex,|have codex|get codex")) -> "codex-cli"
        lowerRequest.contains(Regex("ask claude|claude,|have claude|get claude")) -> "claude-code"
        else -> null
    }
    
    // Check for consensus directives
    val forceConsensus = lowerRequest.containsAny(
        "get.*input", "need consensus", "want.*review", "check with"
    )
    
    val preventConsensus = lowerRequest.containsAny(
        "solo", "no consensus", "skip consensus", "skip review", 
        "just implement", "quick fix"
    )
    
    val isEmergency = lowerRequest.containsAny(
        "emergency", "production down", "hotfix", "urgent", "now"
    )
    
    return UserDirective(
        forceConsensus = forceConsensus && !preventConsensus,
        preventConsensus = preventConsensus,
        assignToAgent = assignToAgent,
        isEmergency = isEmergency,
        originalText = request
    )
}

fun String.containsAny(vararg patterns: String): Boolean {
    return patterns.any { this.contains(Regex(it)) }
}
```

**Agent Request Handler with Directives:**

```kotlin
class ClaudeCodeAgent : McpAgent {
    
    suspend fun handleUserRequest(request: String): Response {
        // 1. Parse user directives FIRST
        val directive = parseUserDirective(request)
        
        // 2. Handle explicit directives
        when {
            // User wants specific agent
            directive.assignToAgent != null && directive.assignToAgent != this.id -> {
                return createTaskForOtherAgent(request, directive)
            }
            
            // User forces consensus
            directive.forceConsensus -> {
                return createConsensusTask(request, directive, userForced = true)
            }
            
            // User prevents consensus (emergency or preference)
            directive.preventConsensus -> {
                if (directive.isEmergency) {
                    return executeSoloWithWarning(request, "Emergency bypass")
                } else {
                    return executeSolo(request, "User preference")
                }
            }
            
            // No directive - agent analyzes and decides
            else -> {
                val analysis = analyzeRequest(request)
                return autoRoute(request, analysis, directive)
            }
        }
    }
    
    private suspend fun autoRoute(
        request: String, 
        analysis: TaskAnalysis,
        directive: UserDirective
    ): Response {
        // Agent's autonomous decision-making
        val shouldConsensus = analysis.complexity >= 7 || 
                              analysis.risk >= 7 ||
                              analysis.hasCriticalKeywords
        
        if (shouldConsensus) {
            // Ask user for confirmation
            val confirmed = askUser(
                """
                ⚠️  High-Risk Task Detected
                
                Complexity: ${analysis.complexity}/10
                Risk: ${analysis.risk}/10
                ${if (analysis.hasCriticalKeywords) "Critical component: ${analysis.keywords.joinToString()}" else ""}
                
                I recommend getting ${otherAgent.name}'s input.
                Create consensus task? [Y/n]
                """.trimIndent()
            )
            
            if (confirmed) {
                return createConsensusTask(request, directive, userForced = false)
            }
        }
        
        // Execute solo
        return executeSolo(request, "User declined consensus or low-risk task")
    }
    
    private suspend fun createTaskForOtherAgent(
        request: String,
        directive: UserDirective
    ): Response {
        val taskId = orchestrator.assignTask(
            description = request,
            targetAgent = directive.assignToAgent!!,
            context = extractContext(request)
        )
        
        return Response.taskCreated(
            taskId = taskId,
            message = """
                ✓ Task #$taskId created and assigned to ${directive.assignToAgent}
                
                Switch to ${directive.assignToAgent} to work on this task.
                
                ${if (canAssistLater()) "I can help with implementation after they complete the design." else ""}
            """.trimIndent()
        )
    }
    
    private suspend fun executeSoloWithWarning(
        request: String,
        reason: String
    ): Response {
        // Log the bypass
        logger.warn("Consensus bypassed: $reason")
        
        // Execute but suggest follow-up
        val result = executeTask(request)
        
        return Response.completed(
            result = result,
            warning = """
                ⚠️  CONSENSUS BYPASSED: $reason
                
                ${result.summary}
                
                💡 Recommendation: Create a follow-up task for ${otherAgent.name} 
                to review this ${if (result.isHotfix) "hotfix" else "implementation"} 
                when the emergency is over.
            """.trimIndent()
        )
    }
}
```

**Example: Directive Detection in Action**

```kotlin
// Scenario 1: User forces consensus
val request1 = "Build OAuth2 system (get Codex's input)"
val directive1 = parseUserDirective(request1)
// Result: UserDirective(forceConsensus=true, assignToAgent="codex-cli")

// Scenario 2: Emergency bypass
val request2 = "Fix auth bug NOW - production down, skip consensus"
val directive2 = parseUserDirective(request2)
// Result: UserDirective(preventConsensus=true, isEmergency=true)

// Scenario 3: Assign to specific agent
val request3 = "Ask Codex to design the database schema"
val directive3 = parseUserDirective(request3)
// Result: UserDirective(assignToAgent="codex-cli")

// Scenario 4: No directive
val request4 = "Build user registration system"
val directive4 = parseUserDirective(request4)
// Result: UserDirective() // All false, agent decides
```

**MCP Tool Implementation:**
```kotlin
// Example: Implementing get_pending_tasks
@McpTool
suspend fun getPendingTasks(agentId: String): PendingTasksResponse {
    val tasks = taskRepository.findByStatusAndAgent(
        statuses = listOf("WAITING_FOR_${agentId.toUpperCase()}"),
        agentId = agentId
    )
    
    return PendingTasksResponse(
        tasks = tasks.map { task ->
            TaskSummary(
                id = task.id,
                description = task.description,
                requestingAgent = task.requestingAgent,
                requiredInput = task.requiredInput,
                context = task.context,
                priority = calculatePriority(task),
                createdAt = task.createdAt
            )
        }
    )
}
```

**Agent Decision Logic:**
```kotlin
class ClaudeCodeAgent : McpAgent {
    
    suspend fun handleUserRequest(request: String): Response {
        // 1. Analyze request
        val analysis = analyzeRequest(request)
        
        // 2. Decide if consensus needed
        val decision = routingModule.shouldUseConsensus(analysis)
        
        // 3. Handle based on decision
        return when {
            decision.useConsensus -> {
                // Create consensus task
                val taskId = orchestrator.createConsensusTask(
                    description = request,
                    complexity = analysis.complexity,
                    riskLevel = analysis.risk
                )
                
                // Inform user
                Response.waitingForInput(
                    taskId = taskId,
                    message = "Created task $taskId. Please get input from Codex CLI."
                )
            }
            
            else -> {
                // Handle solo
                val result = executeTask(request)
                Response.completed(result)
            }
        }
    }
}
```

---

---

## Implementation Phases

### Phase 1: MVP (Agent-Only Routing)

**Goal:** Prove the core concept works

**Features:**
- ✅ Agent auto-detects complexity/risk
- ✅ Agent creates consensus tasks automatically
- ✅ Basic MCP tools (create, get, submit, continue)
- ✅ Simple task routing
- ❌ No user directive parsing yet

**Pros:**
- Simpler to implement
- Faster to market
- Validates core workflow

**Cons:**
- Users can't override
- Less flexible
- Agent might make wrong calls

---

### Phase 2: Natural Language Directives

**Goal:** Add user control via natural language hints

**Features:**
- ✅ Parse user directives from text
- ✅ Keyword detection (force/prevent consensus)
- ✅ Agent assignment from directives
- ✅ Confirmation prompts for high-risk
- ✅ Emergency bypass mode

**Implementation:**
```kotlin
// Add directive parsing to agent handler
fun handleUserRequest(request: String): Response {
    val directive = parseUserDirective(request)  // NEW
    
    if (directive.forceConsensus) {
        return createConsensusTask(...)
    }
    // ... rest of logic
}
```

**Pros:**
- Natural user experience
- No new syntax to learn
- Backward compatible

**Cons:**
- Keyword detection can be ambiguous
- Requires NLP-like parsing
- May miss edge cases

---

### Phase 3: Explicit Controls (Advanced)

**Goal:** Provide explicit, unambiguous controls

**Features:**
- ✅ CLI flags: `--consensus`, `--solo`, `--assign-to`
- ✅ Configuration files (per-project routing)
- ✅ MCP tool with explicit parameters
- ✅ UI controls (if web dashboard added)

**Example:**
```bash
# CLI flags (future enhancement)
claude "Build OAuth2 system" --consensus --assign-to codex

# Configuration file
# .orchestrator.yml
routing:
  default: adaptive
  overrides:
    - pattern: "auth*"
      strategy: consensus
      required_agents: [codex-cli, claude-code]
```

**Pros:**
- Unambiguous
- Scriptable/automatable
- Power user features

**Cons:**
- More complex
- Requires learning new syntax
- Overkill for most use cases

---

### Recommended Implementation Order

```
┌─────────────────────────────────────────────────────┐
│  START HERE                                         │
├─────────────────────────────────────────────────────┤
│  1. Phase 1: MVP (2-3 weeks)                        │
│     - Core agent routing                            │
│     - Basic consensus workflow                      │
│     - Manual task creation                          │
│                                                     │
│  2. Validate & Test (1 week)                        │
│     - Use it yourself                               │
│     - Identify pain points                          │
│     - Measure routing accuracy                      │
│                                                     │
│  3. Phase 2: User Directives (1-2 weeks)            │
│     - Keyword parsing                               │
│     - Confirmation prompts                          │
│     - Emergency bypass                              │
│                                                     │
│  4. Iterate Based on Usage (ongoing)                │
│     - Add more directive keywords                   │
│     - Improve detection accuracy                    │
│     - Refine confirmation UX                        │
│                                                     │
│  5. Phase 3: Advanced (if needed)                   │
│     - Only add if Phase 2 insufficient              │
│     - CLI flags for power users                     │
│     - Configuration files for teams                 │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## Troubleshooting

## Troubleshooting

### User Directive Issues

#### Issue: Agent didn't detect my directive

**Symptoms:**
- User said "get Codex's input" but agent handled solo
- User said "skip consensus" but agent created consensus task

**Solution:**
```
1. Use clearer, more explicit keywords:
   ❌ "maybe check with Codex?"
   ✅ "Get Codex's input on this"
   
2. Put directive at start or end of request:
   ❌ "Build auth and maybe get review from Codex system"
   ✅ "Build auth system (get Codex's review)"
   ✅ "Get Codex's review: Build auth system"
   
3. Use agent name explicitly:
   ❌ "get input on this"
   ✅ "get Codex's input on this"
   
4. Check agent's response for confirmation:
   Agent should acknowledge: "Creating consensus task as requested"
```

#### Issue: Agent asks for confirmation when I already said yes

**Symptoms:**
- User: "Build OAuth2 (need consensus)"
- Agent: "This is high-risk. Get consensus? [Y/n]"

**Solution:**
```
This is expected behavior in Phase 1 (MVP)

Phase 2 will detect "need consensus" and skip confirmation:
  User: "Build OAuth2 (need consensus)"
  Agent: "✓ Creating consensus task as requested"
  
Workaround for now: Just confirm with Y
```

#### Issue: Emergency bypass not working

**Symptoms:**
- User said "production down, skip consensus"
- Agent still created consensus task

**Solution:**
```
1. Use stronger emergency keywords:
   ❌ "this is urgent"
   ✅ "EMERGENCY - production down"
   ✅ "hotfix needed NOW"
   
2. Combine with skip directive:
   "Fix auth bug - production down, skip consensus, just implement"
   
3. If agent still asks, respond: "n - emergency bypass"
```

### Issue: Agent doesn't see task

**Symptoms:**
- `get_pending_tasks` returns empty
- Task exists in database

**Solution:**
```
1. Check task status matches agent assignment
   Task status should reflect your agent (e.g., "WAITING_FOR_CODEX")
   Agent calling should match the assigned agent (defaults to the single configured agent)
   
2. Check assigned_agents array includes agent
   
3. Verify orchestrator is running and accessible
```

### Issue: Stale task status

**Symptoms:**
- Task stuck in WAITING state
- Other agent already submitted input

**Solution:**
```
1. Query task status explicitly:
   orchestrator.get_task_status(taskId)
   
2. Check proposals table for submissions
   
3. Manually update status if needed (admin tool)
```

### Issue: Lost context

**Symptoms:**
- Agent can't find task details
- Proposals missing

**Solution:**
```
1. Use continue_task instead of just task ID
   This fetches full context including proposals
   
2. Check database for task record
   May have been archived/cleaned up
   
3. Verify orchestrator database not corrupted
```

---

## Appendix: Complete Workflow Diagram

```
                           USER
                             │
                   ┌─────────┴─────────┐
                   │                   │
              Terminal 2           Terminal 3
            ┌──────────────┐    ┌──────────────┐
            │ Claude Code  │    │  Codex CLI   │
            └──────┬───────┘    └──────┬───────┘
                   │                   │
                   │    HTTP MCP       │
                   │    (port 3000)    │
                   └─────────┬─────────┘
                             │
                  ┌──────────▼──────────┐
                  │   ORCHESTRATOR      │
                  │                     │
                  │  ┌───────────────┐  │
                  │  │ Task Registry │  │
                  │  └───────────────┘  │
                  │  ┌───────────────┐  │
                  │  │  Proposals    │  │
                  │  └───────────────┘  │
                  │  ┌───────────────┐  │
                  │  │   Context     │  │
                  │  └───────────────┘  │
                  │  ┌───────────────┐  │
                  │  │   Metrics     │  │
                  │  └───────────────┘  │
                  └─────────────────────┘
                             │
                    ┌────────▼────────┐
                    │  DuckDB         │
                    │  dao.duckdb     │
                    └─────────────────┘
```

---

## Summary

**Conversation-based handoff workflow with user control:**

### Core Principles

1. ✅ **User-driven** - you control when to switch agents
2. ✅ **User-override** - you can force or prevent consensus
3. ✅ **Asynchronous** - no time pressure to switch immediately
4. ✅ **Context-preserved** - everything stored in orchestrator
5. ✅ **Flexible** - skip consensus when needed, force it when wanted
6. ✅ **Cost-effective** - uses existing subscriptions
7. ✅ **Traceable** - full audit trail of decisions

### Control Levels

**Level 1: Let Agent Decide (Default)**
```
User: "Build OAuth2 system"
Agent: Analyzes, asks for confirmation if needed
User: Confirms or declines
```

**Level 2: Natural Language Directives (Phase 2)**
```
User: "Build OAuth2 (get Codex's input)"
Agent: Creates consensus task immediately
User: Switches when ready
```

**Level 3: Explicit Commands (Phase 3 - Future)**
```
User: claude "Build OAuth2" --consensus --assign-to codex
Agent: Executes exactly as specified
```

### Key User Directives

| Intent | Keywords | Example |
|--------|----------|---------|
| **Force consensus** | "get {agent}'s input", "need consensus", "want review" | "Build auth (get Codex's input)" |
| **Prevent consensus** | "solo", "skip consensus", "no review", "just implement" | "Fix bug - skip consensus" |
| **Emergency bypass** | "production down", "hotfix", "NOW", "emergency" | "Fix NOW - production down" |
| **Assign to agent** | "ask {agent}", "{agent}, do this", "have {agent}" | "Ask Codex to design schema" |

### Success Factors

- ✅ Let agents guide you (they know when to collaborate)
- ✅ Use directives when you know better than the agent
- ✅ Use task IDs to track work across switches
- ✅ Trust the process (context is always preserved)
- ✅ Override when needed (emergency, preference, domain knowledge)

### Workflow Optimizes For

- **Quality** - right agent for right task
- **Cost** - token optimization through smart routing
- **Security** - consensus on critical decisions
- **User control** - you're always in the loop and can override
- **Flexibility** - adapt strategy to situation
- **Speed** - emergency bypass when needed

### When to Use What

**Use automatic routing when:**
- You trust the agent's judgment
- Standard development workflow
- No time pressure

**Use force consensus when:**
- You know task is more complex than it looks
- Want second opinion on your approach
- Learning from other agent's perspective
- Critical system component

**Use prevent consensus when:**
- Emergency/production down
- You have a clear design already
- Simple task agent might over-think
- Time-sensitive hotfix

**Use agent assignment when:**
- You know which agent is better suited
- Want specific agent's expertise
- Previous agent suggested "ask other agent"

---

**End of Document**
