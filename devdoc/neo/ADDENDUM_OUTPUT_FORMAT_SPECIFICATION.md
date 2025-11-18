# ADDENDUM: Output Format Specification for query_context

**Add this section to**: `README_FOR_CLAUDE_CODE.md` (after "What Gets Better" section)

---

## Output Format Changes: Before and After

### Current Output (DuckDB Only)

`query_context` returns a JSON array with flat ranking:

```json
[
  {
    "chunkId": "chunk-semantic-001",
    "score": 0.95,
    "content": "public fun route(task: Task): Route { ... }",
    "metadata": {
      "provider": "semantic",
      "filePath": "src/main/kotlin/TaskRouter.kt",
      "startLine": 42,
      "endLine": 65
    }
  },
  {
    "chunkId": "chunk-symbol-002",
    "score": 0.88,
    "content": "private fun findBestRoute(routes: List<Route>): Route { ... }",
    "metadata": {
      "provider": "symbol",
      "filePath": "src/main/kotlin/TaskRouter.kt",
      "startLine": 70,
      "endLine": 95
    }
  }
]
```

**Characteristics**: Flat ordering by score, provider-agnostic, no relationship context

---

### Enhanced Output (Neo4j Integration - Phase 3)

After Phase 3 (UI Enhancement), `query_context` returns hierarchical markdown:

```markdown
## com.orchestrator.routing.TaskRouter

### route()

- **Chunk chunk-semantic-001** (score: 0.95)
  - RFR: 0.920 | Structural: 0.980
  - Main routing logic with consensus
  - Related chunks: chunk-consensus-vote-001, chunk-handler-execute-002

### findBestRoute()

- **Chunk chunk-symbol-002** (score: 0.88)
  - RFR: 0.850 | Structural: 0.910
  - Route selection algorithm
  - Related chunks: chunk-graph-traversal-001

### execute()

- **Chunk chunk-semantic-003** (score: 0.82)
  - RFR: 0.810 | Structural: 0.830
  - Executes selected route
  - Related chunks: chunk-logging-001
```

**Characteristics**: 
- Hierarchical by class/method structure
- Shows RFR score (DuckDB relevance) + Structural score (Neo4j relationship)
- Groups related chunks together
- Includes transitive dependencies for context

---

### Comparison

| Aspect | DuckDB (Phase 1-2) | Neo4j Enhanced (Phase 3) |
|--------|-------------------|--------------------------|
| **Format** | JSON array | Hierarchical markdown |
| **Ranking** | By RFR score only | RFR + Structural score |
| **Organization** | Flat list | Grouped by class/method |
| **Context** | Metadata only | Related chunks included |
| **Scores Shown** | Single score | RFR breakdown visible |
| **Human Readable** | No (JSON) | Yes (markdown) |

---

## Phase Timeline: When Format Changes

### Phase 1: Setup (Days 1-2)
```
Status: ✅ Neo4j indexing working
Output: UNCHANGED (still JSON)
Visible: No (running in background)
Action: Index codebase into Neo4j
```

### Phase 2: Integration Testing (Day 3)
```
Status: ✅ Scoring enhanced with Neo4j
Output: UNCHANGED (still JSON)
Visible: ✅ Results more relevant (but same format)
Action: A/B test with `structural_weight = 0.05-0.15`
```

### Phase 3: UX Enhancement (Day 4-5)
```
Status: ✅ Output format changes
Output: CHANGED (now markdown)
Visible: ✅ Users see hierarchical, organized results
Action: Set `use_structured_output = true`
```

**Summary**: Format changes only in Phase 3. Phases 1-2 maintain backward compatibility.

---

## Configuration

Add these options to `fusionagent.toml`:

```toml
[context_engine]
# When to enable hierarchical markdown output
# Options: "never", "phase_3", "always", "auto"
# Default: "auto" (enabled when structural_weight > 0.10)
use_structured_output = "auto"

# When disabled (Phases 1-2), falls back to JSON
use_legacy_json = false  # Set to true to force JSON even in Phase 3

# Structural contribution to final score
# Start low in Phase 2 (0.05), increase to 0.15 by Phase 3
structural_weight = 0.15
```

---

## Fallback Behavior

**If Neo4j is unavailable:**

```kotlin
// In query_context handler
val results = try {
    if (useStructuredOutput) {
        neo4j.enhanceAndOrganize(duckdbResults)  // Returns markdown
    } else {
        duckdbResults  // Returns JSON
    }
} catch (e: Exception) {
    logger.warn("Neo4j unavailable, returning DuckDB results only")
    duckdbResults  // Fallback to flat JSON
}
```

**Output Format if Neo4j Fails**:
- Returns flat JSON (same as Phase 1-2)
- Logs warning: "Neo4j context provider unavailable, using DuckDB-only ranking"
- No errors to user (graceful degradation)

---

## How Claude Should Handle This

### In Phase 1-2 (Current Behavior)
Claude receives JSON search results in current format. No prompt change needed.

### In Phase 3 (New Behavior)
Claude receives hierarchical markdown. Update system prompt:

```
When you receive code results in hierarchical markdown format with class/method 
grouping, use the structure to understand how code is organized. The scores 
(RFR and Structural) indicate confidence:
- RFR Score (0-1): How well the content matches the semantic/symbol/text query
- Structural Score (0-1): How closely related the code is to other matching code
- Final Score: Weighted combination showing overall relevance

Use related chunks to understand context and dependencies.
```

---

## Scoring Breakdown Example

When you see a result like:

```markdown
- **Chunk chunk-1234** (score: 0.92)
  - RFR: 0.890 | Structural: 0.950
```

**This means**:
- **Final Score (0.92)** = `0.85 × 0.890` + `0.15 × 0.950`
  - 85% comes from DuckDB relevance (semantic/symbol/fulltext/git)
  - 15% comes from Neo4j structural relationships
- **RFR (0.890)** = How well query matches the content semantically
- **Structural (0.950)** = How closely related via code structure

**Higher structural score** = Code is called by/calls/similar to other matches

---

## Implementation Checklist

**Phase 1 (Setup)**:
- [ ] Neo4j indexing working
- [ ] Output format: JSON (unchanged)
- [ ] Configuration: structural_weight = 0 (disabled)

**Phase 2 (Integration)**:
- [ ] Enhanced RFR scoring active
- [ ] Output format: JSON (unchanged)
- [ ] Configuration: structural_weight = 0.05
- [ ] Monitor: Are results more relevant?
- [ ] Increase structural_weight gradually to 0.15

**Phase 3 (UX)**:
- [ ] Output format: Markdown (hierarchical)
- [ ] Configuration: use_structured_output = true
- [ ] Configuration: structural_weight = 0.15
- [ ] Result organizer active
- [ ] Verify: Claude can parse markdown results

---

## Common Questions

**Q: Will changing output format break my code?**  
A: Only if you're parsing query_context results. This is designed for Claude consumption (markdown), not API clients (JSON). If you have API clients, keep `use_legacy_json = true` in config.

**Q: Can I use both formats?**  
A: Yes - add config option `output_format = "auto"` to choose based on context.

**Q: What if I want to stay on Phase 2?**  
A: Set `use_structured_output = false` to keep JSON format indefinitely.

**Q: How do I debug scoring?**  
A: Look at RFR vs Structural scores. If Structural score is very different from final score, Neo4j relationships are having big impact.

**Q: Performance impact of Phase 3?**  
A: Minimal. Organization happens at presentation layer, not in scoring. Query time stays same, just format changes.

---

## Migration Path

```
Phase 1 (Current)          Phase 2 (Enhanced)         Phase 3 (Optimized)
├─ DuckDB indexing         ├─ DuckDB + Neo4j          ├─ DuckDB + Neo4j
├─ DuckDB RFR scoring      ├─ RFR + Structural        ├─ RFR + Structural
├─ JSON output             ├─ JSON output             ├─ Markdown output
└─ structural_weight = 0   ├─ structural_weight=0.05  └─ structural_weight=0.15
                           ├─ A/B testing             └─ use_structured_output=true
                           └─ Gradual ramp
```

**No breaking changes**. Each phase is backward compatible. Phase 2→3 transition only visible to users.

---

## What Changes for Claude Code

1. **Step 11 (Integration)**: Note that output format change happens in Phase 3, not Phase 2
2. **ResultOrganizer** (Step 8): This only outputs in Phase 3; Phase 1-2 skip it
3. **Configuration** (Step 12): Add the three config options above
4. **Testing** (Step 9): Test both JSON and markdown output paths

---

## Success Criteria

✅ Phase 1 Complete:
- Neo4j indexing working
- query_context still returns JSON
- No visible change to users

✅ Phase 2 Complete:
- Results are more relevant (validated by A/B test)
- query_context still returns JSON
- Scoring improved

✅ Phase 3 Complete:
- query_context returns hierarchical markdown
- Related chunks shown
- Claude understands structure
- Scoring breakdown visible

---

**Add this addendum to your Neo4j documentation before passing to Claude Code.**

It clarifies:
1. ✅ What output format is (before/after)
2. ✅ When it changes (Phase 3, not Phase 2)
3. ✅ Fallback behavior (graceful degradation)
4. ✅ Configuration options (control the transition)
5. ✅ How Claude should handle it (prompt guidance)
