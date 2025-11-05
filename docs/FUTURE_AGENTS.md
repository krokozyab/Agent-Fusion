# Future Agent Implementations

## Overview

This document describes placeholder agents that are ready for implementation when the underlying services become available.

## Placeholder Agents

### 1. Google Gemini

**Status:** Placeholder (not implemented)

**Strengths:**
- Multimodal processing (text, images, video)
- Long context window (1M+ tokens)
- Strong data analysis capabilities

**Planned Capabilities:**
- Code Generation (88)
- Data Analysis (92)
- Architecture (85)
- Documentation (87)

**Use Cases:**
- Multimodal tasks (analyzing images, videos)
- Very long context requirements
- Data analysis and visualization
- Complex reasoning tasks

**Implementation Path:**
1. Wait for Gemini MCP support OR implement direct API
2. Add `AgentType.GEMINI` to enum
3. Implement `sendMcpRequest()` method
4. Create factory and register via SPI
5. Enable in configuration

### 2. Alibaba Qwen

**Status:** Placeholder (not implemented)

**Strengths:**
- Cost-effective alternative
- Multilingual support (especially Chinese)
- Good documentation capabilities

**Planned Capabilities:**
- Code Generation (85)
- Code Review (82)
- Documentation (88)
- Data Analysis (86)

**Use Cases:**
- Cost-sensitive projects
- Multilingual documentation
- Chinese language support
- High-volume tasks

**Implementation Path:**
1. Wait for Qwen MCP support OR implement direct API
2. Add `AgentType.QWEN` to enum
3. Implement `sendMcpRequest()` method
4. Create factory and register via SPI
5. Enable in configuration

### 3. DeepSeek Coder

**Status:** Placeholder (not implemented)

**Strengths:**
- Specialized for code generation
- High code quality
- Strong refactoring capabilities

**Planned Capabilities:**
- Code Generation (93)
- Refactoring (90)
- Code Review (88)
- Debugging (86)
- Test Writing (85)

**Use Cases:**
- Pure code generation tasks
- Code refactoring projects
- Code quality improvement
- Alternative to Claude Code

**Implementation Path:**
1. Wait for DeepSeek Coder MCP support OR implement direct API
2. Add `AgentType.DEEPSEEK_CODER` to enum
3. Implement `sendMcpRequest()` method
4. Create factory and register via SPI
5. Enable in configuration

## Capability Comparison

### All Agents (Current + Future)

| Agent           | Status      | Code Gen | Architecture | Data Analysis | Cost      |
|-----------------|-------------|----------|--------------|---------------|-----------|
| Claude Code     | ✅ Active   | 95       | 90           | -             | High      |
| Codex CLI       | ✅ Active   | 85       | 95           | 90            | High      |
| Gemini          | 🔜 Planned  | 88       | 85           | 92            | Medium    |
| Qwen            | 🔜 Planned  | 85       | -            | 86            | Low       |
| DeepSeek Coder  | 🔜 Planned  | 93       | -            | -             | Medium    |

## Configuration

### Current (Active Agents)

```toml
[agents.claude-code]
type = "CLAUDE_CODE"
[agents.claude-code.extra]
url = "http://localhost:3000/mcp"

[agents.codex-cli]
type = "CODEX_CLI"
[agents.codex-cli.extra]
url = "http://localhost:3001/mcp"
```

### Future (When Implemented)

```toml
# Uncomment when ready
# [agents.gemini]
# type = "GEMINI"
# [agents.gemini.extra]
# url = "http://localhost:3002/mcp"

# [agents.qwen]
# type = "CUSTOM"  # Will be QWEN
# [agents.qwen.extra]
# url = "http://localhost:3003/mcp"

# [agents.deepseek-coder]
# type = "CUSTOM"  # Will be DEEPSEEK_CODER
# [agents.deepseek-coder.extra]
# url = "http://localhost:3004/mcp"
```

## Implementation Checklist

When implementing a placeholder agent:

- [ ] Add agent type to `AgentType` enum (if needed)
- [ ] Implement `sendMcpRequest()` method
- [ ] Create `AgentFactory` implementation
- [ ] Register factory in `META-INF/services`
- [ ] Write comprehensive tests
- [ ] Update documentation
- [ ] Remove TODO markers
- [ ] Enable in example configuration
- [ ] Test with real MCP server
- [ ] Update routing strategies

## Testing Placeholders

Placeholders can be tested for structure:

```kotlin
@Test
fun `placeholder compiles and has correct structure`() {
    val connection = McpConnection("http://localhost:3002")
    val agent = GeminiAgent(connection)
    
    // Structure tests
    assertEquals(AgentType.GEMINI, agent.type)
    assertTrue(agent.capabilities.isNotEmpty())
    assertTrue(agent.strengths.isNotEmpty())
    
    // Not implemented
    assertThrows<NotImplementedError> {
        runBlocking {
            agent.executeTask(TaskId("test"), "test")
        }
    }
}
```

## Priority Order

### Phase 1: Current (Complete)
- ✅ Claude Code
- ✅ Codex CLI

### Phase 2: High Priority
- 🔜 Gemini (multimodal, long context)
- 🔜 DeepSeek Coder (code specialist)

### Phase 3: Medium Priority
- 🔜 Qwen (cost-effective)

### Phase 4: Future
- Additional models as needed
- Custom specialized agents

## Benefits of Placeholders

1. **Structure Ready** - Extension points defined
2. **Documentation** - Clear capabilities and use cases
3. **Configuration** - Examples provided
4. **Discovery** - Easy to find in codebase
5. **Consistency** - Same patterns as active agents

## When to Implement

Implement a placeholder agent when:
- MCP support becomes available
- Direct API is stable and documented
- Business need justifies the effort
- Testing infrastructure is ready
- Configuration is straightforward

## Notes

- All placeholders throw `NotImplementedError`
- Disabled by default in configuration
- Clear TODO markers for implementation
- Follow same patterns as active agents
- Comprehensive documentation provided
