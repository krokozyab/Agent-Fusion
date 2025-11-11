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

