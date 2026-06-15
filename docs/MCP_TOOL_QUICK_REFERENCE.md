# MCP Tools: Quick Reference Guide

This guide provides a simplified decision-making framework for choosing the correct MCP tool.

---

## 1. Starting New Work: Task Creation

**Question: What is the user's intent for this new task?**

*   **"I want a specific agent to do this."**
    *   **Tool:** `assign_task`
    *   **Action:** Assign the task directly to the specified agent.

*   **"This is complex, high-risk, or needs multiple opinions."**
    *   **Tool:** `create_consensus_task`
    *   **Action:** Create a task that requires input from at least two agents.
    *   **Trigger:** Use this if `complexity >= 7`, `risk >= 7`, or the user says things like "get another opinion" or "review this."

*   **"This is a straightforward, low-risk task for one agent."**
    *   **Tool:** `create_simple_task`
    *   **Action:** Create a task for solo execution.
    *   **Trigger:** Use this if the user says "just do it" or "quick fix," and the task is low-risk.

---

## 2. Checking on Existing Work: Task Inquiry

**Question: What do I need to know about an existing task?**

*   **"What work is waiting for me?"**
    *   **Tool:** `get_pending_tasks`
    *   **Action:** Retrieves your "inbox" of tasks that require your input.

*   **"What is the current status of task-123?"**
    *   **Tool:** `get_task_status`
    *   **Action:** Provides a lightweight summary (status, assignees) without the full context.

*   **"I need to see everything about task-123 to decide what to do."**
    *   **Tool:** `continue_task`
    *   **Action:** Loads the complete context: description, all proposals, and conversation history. Use this when you need to analyze before acting.

---

## 3. Contributing to a Task

**Question: How should I add my work to a task?**

*   **"I have analyzed the task and am ready to submit my input (plan, review, etc.)."**
    *   **Tool:** `respond_to_task` **(HIGHLY RECOMMENDED)**
    *   **Action:** This is the standard, one-step way to load the task context and submit your response. It combines `continue_task` and `submit_input` into a single, efficient call.

*   **"I need to review the full context *before* I even decide *if* I should respond."**
    *   **Tools:** `continue_task` then `submit_input`
    *   **Action:** Use this two-step process only when you need a separate analysis phase before committing your input.

---

## 4. Finishing a Task

**CRITICAL QUESTION: Did I create this task?**

*   **YES, I created this task, and all work (including reviews) is complete.**
    *   **Tool:** `complete_task`
    *   **Action:** Mark the task as finished and document the final outcome.

*   **NO, I did not create this task. I was asked to provide input.**
    *   **Tool:** `respond_to_task` or `submit_input`
    *   **Action:** Submit your contribution.
    *   **NEVER use `complete_task` on a task you didn't create.** Your job is to provide your piece of the puzzle, not to declare the whole puzzle finished.

---

## 5. Searching the Codebase: Context Tools

**Question: What am I trying to find in the code?**

*   **"I need to find code by meaning / keywords / description."**
    *   **Tool:** `query_context`
    *   **Action:** Semantic + symbol + full-text search with RRF fusion, MMR diversification, and optional graph-link expansion. This is the default for open-ended discovery.
    *   **Use when:** "where is auth handled?", "find the PGP encryption implementation", "show me retry logic", exploring an unfamiliar module, looking for patterns to reuse.
    *   **Not for:** computing blast radius of a diff — it ranks by relevance, not by actual call-graph reachability, so it may miss affected sites.

*   **"I'm changing these lines. What else might break? Which tests should I run?"**
    *   **Tool:** `get_impact_radius`
    *   **Action:** Deterministic reverse traversal of the code graph from the changed chunks. Returns seeds (edited code) + transitive callers/dependents (via `CALLS` / `DEPENDS_ON` / `MODIFIES`) + tests that cover the affected symbols (via `COVERS` edges emitted from test files). No embeddings, no ranking — predictable recall.
    *   **Use when:** reviewing a diff, planning a refactor, pre-merge safety check, answering "is it safe to change X?" with graph evidence, collecting review context under a token budget.
    *   **Inputs:** either `paths: [...]` (whole-file impact per path) or `changes: [{path, startLine, endLine}, ...]` (line-range precision). Knobs: `maxDepth` (default 2), `includeTests` (default true), `tokenBudget` (default 8000).
    *   **Not for:** "find code that does X" — use `query_context` for semantic discovery. The graph only knows edges that already exist.

*   **"How is the context system doing right now?"**
    *   **Tool:** `get_context_stats`
    *   **Action:** Provider status, storage stats, language distribution, recent query activity. Use for debugging retrieval or monitoring index growth.

*   **"Files changed on disk — re-index them."**
    *   **Tool:** `refresh_context` (incremental) or `rebuild_context` (full wipe + reindex).

### `query_context` vs `get_impact_radius` — quick rule

| You have… | You want to know… | Use |
|---|---|---|
| A concept or keyword | Where is it implemented? | `query_context` |
| A diff / edited lines | What might break? What tests cover it? | `get_impact_radius` |
| A symbol name | Where is it defined? | `query_context` (symbol provider) |
| A symbol name | Who calls it transitively? | `get_impact_radius` with `paths=[<symbol's file>]` |
| Nothing specific | General exploration | `query_context` |

Rule of thumb: `query_context` answers **"find me…"**, `get_impact_radius` answers **"what depends on…"**. If you're about to edit code, run `get_impact_radius` first so review context is seeded by the actual call graph instead of keyword similarity.

