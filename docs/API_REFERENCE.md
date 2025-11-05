# MCP API Reference

Complete reference for the Codex to Claude Orchestrator MCP (Model Context Protocol) server.

## Base URL

```
http://localhost:8080
```

## Authentication

Currently no authentication required. Future versions may add API key authentication.

---

## Health Check

### GET /healthz

Check server health status.

**Response:**
```json
{
  "status": "ok",
  "timestamp": "2025-10-05T23:05:00Z"
}
```

---

## MCP Tools

### List Available Tools

**Endpoint:** `GET /mcp/tools`

**Response:**
```json
{
  "tools": [
    {
      "name": "create_simple_task",
      "description": "Create a single-agent task",
      "inputSchema": { ... }
    },
    ...
  ]
}
```

### Invoke Tool

**Endpoint:** `POST /mcp/tools/call`

**Request Body:**
```json
{
  "name": "tool_name",
  "params": { ... }
}
```

---

## Tool: create_simple_task

Create a single-agent (SOLO) task with optional user directives.

### Parameters

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `title` | string | Yes | - | Task title (non-blank) |
| `description` | string | No | null | Detailed task description |
| `type` | string | No | "IMPLEMENTATION" | Task type (see TaskType enum) |
| `complexity` | integer | No | 5 | Complexity score (1-10) |
| `risk` | integer | No | 5 | Risk level (1-10) |
| `assigneeIds` | string[] | No | [] | Agent IDs to assign |
| `dependencyIds` | string[] | No | [] | Task IDs this depends on |
| `dueAt` | string | No | null | ISO-8601 timestamp |
| `metadata` | object | No | {} | Key-value metadata |
| `directives` | object | No | null | User directives (see below) |

#### Directives Object

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `skipConsensus` | boolean | false | Set `true` to force solo routing |
| `assignToAgent` | string | null | Assign to specific agent ID |
| `immediate` | boolean | false | Emergency execution |
| `isEmergency` | boolean | false | Alias for immediate |
| `notes` | string | null | Additional notes |
| `originalText` | string | null | Original user request text |

#### TaskType Enum

- `IMPLEMENTATION` - Code implementation
- `ARCHITECTURE` - System design
- `REVIEW` - Code review
- `RESEARCH` - Research task
- `TESTING` - Test creation
- `DOCUMENTATION` - Documentation
- `PLANNING` - Planning task
- `BUGFIX` - Bug fix

### Response

```json
{
  "taskId": "task-123e4567-e89b-12d3-a456-426614174000",
  "status": "PENDING",
  "routing": "SOLO",
  "primaryAgentId": "claude-code",
  "participantAgentIds": ["claude-code"],
  "warnings": []
}
```

### Examples

#### Basic Task

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "create_simple_task",
    "params": {
      "title": "Implement user authentication",
      "description": "Add JWT-based authentication to the API",
      "type": "IMPLEMENTATION",
      "complexity": 5,
      "risk": 3
    }
  }'
```

#### Emergency Task

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "create_simple_task",
    "params": {
      "title": "Fix critical security vulnerability",
      "type": "BUGFIX",
      "complexity": 8,
      "risk": 10,
      "directives": {
        "isEmergency": true,
        "notes": "Production down - immediate fix required"
      }
    }
  }'
```

#### Assign to Specific Agent

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "create_simple_task",
    "params": {
      "title": "Write API documentation",
      "type": "DOCUMENTATION",
      "directives": {
        "assignToAgent": "claude-code"
      }
    }
  }'
```

### Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 400 | "title cannot be blank" | Missing or empty title |
| 400 | "complexity must be 1..10" | Invalid complexity value |
| 400 | "risk must be 1..10" | Invalid risk value |
| 400 | "Invalid type 'X'" | Unknown task type |
| 400 | "dueAt must be ISO-8601 Instant" | Invalid date format |

---

## Tool: create_consensus_task

Create a multi-agent consensus task requiring collaboration.

### Parameters

Same as `create_simple_task` with different directives:

#### Directives Object

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `forceConsensus` | boolean | true | Force consensus routing |
| `preventConsensus` | boolean | false | Prevent consensus (ignored) |
| `assignToAgent` | string | null | Prefer specific agent |
| `isEmergency` | boolean | false | Emergency flag |
| `notes` | string | null | Additional notes |
| `originalText` | string | null | Original user request |

### Response

```json
{
  "taskId": "task-123e4567-e89b-12d3-a456-426614174000",
  "status": "PENDING",
  "routing": "CONSENSUS",
  "primaryAgentId": "claude-code",
  "participantAgentIds": ["claude-code", "codex-cli"],
  "warnings": []
}
```

### Examples

#### Architecture Decision

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "create_consensus_task",
    "params": {
      "title": "Design payment processing architecture",
      "description": "Design secure, scalable payment system",
      "type": "ARCHITECTURE",
      "complexity": 9,
      "risk": 8
    }
  }'
```

#### Security Review

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "create_consensus_task",
    "params": {
      "title": "Security audit of authentication module",
      "type": "REVIEW",
      "complexity": 7,
      "risk": 9,
      "directives": {
        "notes": "Critical security review - need multiple perspectives"
      }
    }
  }'
```

### Error Codes

Same as `create_simple_task` plus:

| Code | Message | Description |
|------|---------|-------------|
| 400 | "Conflicting directives: forceConsensus and preventConsensus" | Both flags set to true |

---

## Tool: get_pending_tasks

Retrieve pending tasks for the active agent. When only one agent is configured, the call can omit `agentId` or use aliases like `"user"`/`"me"`.

### Parameters

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `agentId` | string | No | single configured agent | Agent ID to query (aliases `user`/`me` map to the default agent) |
| `statuses` | string[] | No | ["PENDING"] | Task statuses to filter |
| `limit` | integer | No | unlimited | Maximum tasks to return (0-1000) |

#### TaskStatus Enum

- `PENDING` - Awaiting execution
- `IN_PROGRESS` - Currently executing
- `WAITING_INPUT` - Waiting for agent input
- `COMPLETED` - Successfully completed
- `FAILED` - Failed execution

### Response

```json
{
  "agentId": "claude-code",
  "count": 2,
  "tasks": [
    {
      "id": "task-123",
      "title": "Implement authentication",
      "status": "PENDING",
      "type": "IMPLEMENTATION",
      "priority": 53,
      "createdAt": "2025-10-05T23:00:00Z",
      "dueAt": "2025-10-06T23:00:00Z",
      "contextPreview": "Add JWT-based authentication to the API..."
    }
  ]
}
```

### Examples

#### Get Pending Tasks

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "get_pending_tasks",
    "params": {}
  }'
```

#### Get All Active Tasks

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "get_pending_tasks",
    "params": {
      "agentId": "user",
      "statuses": ["PENDING", "IN_PROGRESS", "WAITING_INPUT"],
      "limit": 10
    }
  }'
```

### Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 400 | "agentId is required when multiple agents are configured" | No default agent available |
| 400 | "Unknown agentId 'X'" | Provided agent alias not recognized |
| 400 | "Invalid status 'X'" | Unknown status value |

---

## Tool: get_task_status

Get current status and metadata for a specific task.

### Parameters

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `taskId` | string | Yes | Task ID to query |

### Response

```json
{
  "taskId": "task-123",
  "status": "IN_PROGRESS",
  "type": "IMPLEMENTATION",
  "routing": "SOLO",
  "assignees": ["claude-code"],
  "createdAt": "2025-10-05T23:00:00Z",
  "updatedAt": "2025-10-05T23:05:00Z",
  "dueAt": null,
  "metadata": {
    "priority": "high",
    "tags": "auth,security"
  }
}
```

### Example

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "get_task_status",
    "params": {
      "taskId": "task-123e4567-e89b-12d3-a456-426614174000"
    }
  }'
```

### Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 400 | "taskId cannot be blank" | Missing task ID |
| 404 | "Task with id 'X' not found" | Task doesn't exist |

---

## Tool: submit_input

Submit agent input/proposal for a task in progress or awaiting input.

### Parameters

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `taskId` | string | Yes | - | Task ID |
| `agentId` | string | No | single configured agent | Agent submitting input (aliases `user`/`me` map to default) |
| `content` | any | Yes | - | Input content (any JSON type) |
| `inputType` | string | No | "OTHER" | Type of input (see InputType) |
| `confidence` | number | No | 0.5 | Confidence score (0.0-1.0) |
| `metadata` | object | No | {} | Additional metadata |

#### InputType Enum

- `ARCHITECTURAL_PLAN` - Architecture design
- `CODE_REVIEW` - Code review feedback
- `IMPLEMENTATION_PLAN` - Implementation plan
- `TEST_PLAN` - Testing strategy
- `REFACTORING_SUGGESTION` - Refactoring proposal
- `RESEARCH_SUMMARY` - Research findings
- `OTHER` - Other input type

### Response

```json
{
  "taskId": "task-123",
  "proposalId": "proposal-456",
  "inputType": "ARCHITECTURAL_PLAN",
  "taskStatus": "IN_PROGRESS",
  "message": "Input accepted and task status updated to IN_PROGRESS"
}
```

### Examples

#### Submit Architecture Plan

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "submit_input",
    "params": {
      "taskId": "task-123",
      "content": {
        "approach": "Microservices architecture",
        "components": ["API Gateway", "Auth Service", "Payment Service"],
        "rationale": "Scalability and maintainability"
      },
      "inputType": "ARCHITECTURAL_PLAN",
      "confidence": 0.85
    }
  }'
```

#### Submit Code Review

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "submit_input",
    "params": {
      "taskId": "task-456",
      "agentId": "user",
      "content": {
        "issues": ["Missing error handling", "SQL injection risk"],
        "suggestions": ["Add try-catch blocks", "Use parameterized queries"]
      },
      "inputType": "CODE_REVIEW",
      "confidence": 0.9
    }
  }'
```

### Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 400 | "taskId cannot be blank" | Missing task ID |
| 400 | "agentId is required when multiple agents are configured" | No default agent available |
| 400 | "Unknown agentId 'X'" | Provided agent alias not recognized |
| 400 | "confidence must be in [0.0, 1.0]" | Invalid confidence value |
| 400 | "Invalid inputType 'X'" | Unknown input type |
| 404 | "Task 'X' does not exist" | Task not found |
| 409 | "Task 'X' is in status Y; expected IN_PROGRESS or WAITING_INPUT" | Task not ready to accept input |

---

## Tool: assign_task

Create a solo task and assign it directly to a specific agent.

### Parameters

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `title` | string | Yes | - | Task title |
| `targetAgent` | string | No | single configured agent | Agent to assign to (aliases `user`/`me` map to default) |
| `description` | string | No | - | Task description |
| `type` | string | No | `IMPLEMENTATION` | Task type |
| `complexity` | integer | No | 5 | Complexity (1-10) |
| `risk` | integer | No | 5 | Risk (1-10) |
| `dependencyIds` | string[] | No | [] | Dependent task IDs |
| `dueAt` | string | No | - | ISO-8601 due timestamp |
| `metadata` | object | No | {} | Custom metadata |
| `directives` | object | No | - | Immediate/emergency directives |

### Response

```json
{
  "taskId": "task-abc",
  "status": "PENDING",
  "routing": "SOLO",
  "primaryAgentId": "codex-cli",
  "participantAgentIds": ["codex-cli"],
  "warnings": []
}
```

### Example

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "assign_task",
    "params": {
      "title": "Review database migrations",
      "targetAgent": "user",
      "type": "REVIEW",
      "risk": 4
    }
  }'
```

### Error Codes

| Code | Message | Description |
|------|---------|-------------|
| 400 | "title cannot be blank" | Missing title |
| 400 | "agentId is required when multiple agents are configured" | No default agent available |
| 400 | "Agent 'X' does not exist" | Provided agent alias not recognized |
| 409 | "Agent 'X' is offline and cannot be assigned tasks right now" | Cannot assign to offline agent |

---

## Tool: continue_task

Continue execution of a task after receiving input.

### Parameters

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `taskId` | string | Yes | Task ID to continue |
| `agentId` | string | Yes | Agent continuing task |

### Response

```json
{
  "taskId": "task-123",
  "status": "IN_PROGRESS",
  "context": {
    "proposals": [...],
    "previousInputs": [...]
  },
  "message": "Task resumed"
}
```

### Example

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "continue_task",
    "params": {
      "taskId": "task-123",
      "agentId": "claude-code"
    }
  }'
```

---

## Tool: complete_task

Mark a task as completed.

### Parameters

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `taskId` | string | Yes | Task ID to complete |
| `agentId` | string | Yes | Agent completing task |
| `result` | any | No | Task result/output |

### Response

```json
{
  "taskId": "task-123",
  "status": "COMPLETED",
  "completedAt": "2025-10-05T23:30:00Z",
  "message": "Task completed successfully"
}
```

### Example

```bash
curl -X POST http://localhost:8080/mcp/tools/call \
  -H "Content-Type: application/json" \
  -d '{
    "name": "complete_task",
    "params": {
      "taskId": "task-123",
      "agentId": "claude-code",
      "result": {
        "filesModified": ["auth.kt", "user.kt"],
        "testsAdded": 5,
        "summary": "JWT authentication implemented"
      }
    }
  }'
```

---

## MCP Resources

### List Available Resources

**Endpoint:** `GET /mcp/resources`

**Response:**
```json
{
  "resources": [
    {
      "uri": "tasks://",
      "name": "Tasks",
      "description": "Query tasks with filters"
    },
    {
      "uri": "metrics://",
      "name": "Metrics",
      "description": "Query metrics and analytics"
    }
  ]
}
```

### Fetch Resource

**Endpoint:** `GET /mcp/resources/fetch?uri={uri}`

---

## Resource: tasks://

Query tasks with flexible filters.

### URI Format

```
tasks://?status={status}&agent={agentId}&type={type}&limit={limit}
```

### Query Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | string | Filter by status (PENDING, IN_PROGRESS, etc.) |
| `agent` | string | Filter by assigned agent ID |
| `type` | string | Filter by task type |
| `limit` | integer | Maximum results (default: 100) |

### Examples

#### All Pending Tasks

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=tasks://?status=PENDING"
```

#### Tasks for Specific Agent

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=tasks://?agent=claude-code&limit=10"
```

#### Architecture Tasks

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=tasks://?type=ARCHITECTURE"
```

### Response

```json
{
  "uri": "tasks://?status=PENDING",
  "count": 5,
  "tasks": [
    {
      "id": "task-123",
      "title": "Implement authentication",
      "status": "PENDING",
      "type": "IMPLEMENTATION",
      "routing": "SOLO",
      "assignees": ["claude-code"],
      "createdAt": "2025-10-05T23:00:00Z"
    }
  ]
}
```

---

## Resource: metrics://

Query metrics and analytics data.

### URI Format

```
metrics://{category}?from={timestamp}&to={timestamp}&agent={agentId}
```

### Categories

- `tokens` - Token usage statistics
- `performance` - Performance metrics
- `decisions` - Decision analytics
- `alerts` - System alerts

### Query Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `from` | string | Start timestamp (ISO-8601) |
| `to` | string | End timestamp (ISO-8601) |
| `agent` | string | Filter by agent ID |

### Examples

#### Token Usage

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=metrics://tokens"
```

**Response:**
```json
{
  "category": "tokens",
  "totalTokens": 125000,
  "byAgent": {
    "claude-code": 75000,
    "codex-cli": 50000
  },
  "byTask": {
    "task-123": 25000,
    "task-456": 30000
  },
  "period": {
    "from": "2025-10-01T00:00:00Z",
    "to": "2025-10-05T23:59:59Z"
  }
}
```

#### Performance Metrics

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=metrics://performance?agent=claude-code"
```

**Response:**
```json
{
  "category": "performance",
  "agent": "claude-code",
  "metrics": {
    "avgTaskDuration": 1800,
    "tasksCompleted": 45,
    "successRate": 0.95,
    "avgConfidence": 0.82
  }
}
```

#### Decision Analytics

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=metrics://decisions"
```

**Response:**
```json
{
  "category": "decisions",
  "totalDecisions": 23,
  "consensusReached": 20,
  "consensusRate": 0.87,
  "avgAgreement": 0.78,
  "strategiesUsed": {
    "VOTING": 12,
    "REASONING_QUALITY": 8,
    "TOKEN_OPTIMIZATION": 3
  }
}
```

#### System Alerts

```bash
curl "http://localhost:8080/mcp/resources/fetch?uri=metrics://alerts"
```

**Response:**
```json
{
  "category": "alerts",
  "activeAlerts": 2,
  "alerts": [
    {
      "id": "alert-1",
      "severity": "WARNING",
      "message": "High token usage detected",
      "timestamp": "2025-10-05T23:00:00Z"
    },
    {
      "id": "alert-2",
      "severity": "INFO",
      "message": "Agent codex-cli offline",
      "timestamp": "2025-10-05T22:30:00Z"
    }
  ]
}
```

---

## Error Response Format

All errors follow a consistent format:

```json
{
  "error": {
    "code": 400,
    "message": "Invalid parameter: complexity must be 1..10",
    "details": {
      "field": "complexity",
      "value": 15,
      "constraint": "1..10"
    }
  }
}
```

### HTTP Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 400 | Bad Request - Invalid parameters |
| 404 | Not Found - Resource doesn't exist |
| 409 | Conflict - Invalid state transition |
| 500 | Internal Server Error |

---

## Rate Limiting

Currently no rate limiting. Future versions may implement:
- 100 requests/minute per client
- 1000 requests/hour per client

---

## Versioning

API version: `v1`

Future versions will use URL versioning:
```
http://localhost:8080/v2/mcp/tools/call
```

---

## SDK Examples

### Kotlin

```kotlin
val client = McpClient("http://localhost:8080")

val result = client.callTool(
    name = "create_simple_task",
    params = mapOf(
        "title" to "Implement authentication",
        "type" to "IMPLEMENTATION",
        "complexity" to 5
    )
)
```

### Python

```python
import requests

response = requests.post(
    "http://localhost:8080/mcp/tools/call",
    json={
        "name": "create_simple_task",
        "params": {
            "title": "Implement authentication",
            "type": "IMPLEMENTATION",
            "complexity": 5
        }
    }
)
result = response.json()
```

### JavaScript

```javascript
const response = await fetch('http://localhost:8080/mcp/tools/call', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    name: 'create_simple_task',
    params: {
      title: 'Implement authentication',
      type: 'IMPLEMENTATION',
      complexity: 5
    }
  })
});
const result = await response.json();
```

---

## Best Practices

### Task Creation

1. **Use descriptive titles** - Clear, concise task names
2. **Set appropriate complexity/risk** - Helps routing decisions
3. **Include context in description** - Provide sufficient detail
4. **Use metadata** - Store additional context as key-value pairs

### Directives

1. **Use sparingly** - Let orchestrator make routing decisions
2. **Emergency flag** - Only for production issues
3. **Agent assignment** - Use when domain expertise required
4. **Document reasoning** - Use notes field to explain directives

### Consensus Tasks

1. **High-risk decisions** - Architecture, security, critical changes
2. **Multiple perspectives** - When diverse input valuable
3. **Not for simple tasks** - Avoid consensus overhead for routine work

### Input Submission

1. **Structured content** - Use JSON objects for complex input
2. **Set confidence** - Reflect certainty in proposal
3. **Include rationale** - Explain reasoning in content
4. **Use appropriate type** - Select correct InputType

---

## Troubleshooting

### Common Issues

**Task not accepting input**
- Check task status is `WAITING_INPUT`
- Verify task exists with `get_task_status`

**Agent not found**
- Ensure agent is configured in `agents.toml`
- Check agent is enabled and connected

**Invalid parameters**
- Review parameter types and constraints
- Check enum values match exactly (case-sensitive)

**Consensus not triggered**
- Verify complexity/risk thresholds
- Check `forceConsensus` directive if needed

---

## Support

For issues or questions:
- GitHub Issues: [repository-url]
- Documentation: See CLAUDE.md for architecture details
- Examples: See integration tests in `src/test/kotlin`
