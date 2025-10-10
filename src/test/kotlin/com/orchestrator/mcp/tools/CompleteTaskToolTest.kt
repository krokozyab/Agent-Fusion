package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.*
import kotlin.test.*
import java.time.Instant

class CompleteTaskToolTest {

    @BeforeTest
    fun resetDb() {
        Database.withConnection { conn ->
            conn.createStatement().use { st ->
                // FK-safe cleanup order
                st.executeUpdate("DELETE FROM context_snapshots")
                st.executeUpdate("DELETE FROM conversation_messages")
                st.executeUpdate("DELETE FROM decisions")
                st.executeUpdate("DELETE FROM proposals")
                st.executeUpdate("DELETE FROM metrics_timeseries")
                st.executeUpdate("DELETE FROM tasks")
            }
        }
    }

    @Test
    fun completes_simple_task_without_decision() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-simple"),
            title = "Simple task",
            description = "No consensus needed",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = CompleteTaskTool()
        val result = tool.execute(
            CompleteTaskTool.Params(
                taskId = task.id.value,
                resultSummary = "Task completed successfully",
                completedBy = "claude-code",
                tokenMetrics = CompleteTaskTool.Params.TokenMetricsDTO(
                    inputTokens = 100,
                    outputTokens = 50
                )
            )
        )

        assertEquals(task.id.value, result.taskId)
        assertEquals("COMPLETED", result.status)
        assertNull(result.decisionId)
        assertTrue(result.warnings.isEmpty())

        // Verify metrics were recorded
        assertTrue(result.recordedMetrics.containsKey("task.tokens.input"))
        assertTrue(result.recordedMetrics.containsKey("task.tokens.output"))
        assertTrue(result.recordedMetrics.containsKey("task.tokens.total"))
        assertEquals(150.0, result.recordedMetrics["task.tokens.total"])

        // Verify task was updated in database
        val updated = TaskRepository.findById(task.id)
        assertNotNull(updated)
        assertEquals(TaskStatus.COMPLETED, updated.status)
        assertEquals("claude-code", updated.metadata["completedBy"])
    }

    @Test
    fun completes_consensus_task_with_decision() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-consensus"),
            title = "Consensus task",
            description = "Needs decision",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code"), AgentId("codex-cli")),
            dependencies = emptySet(),
            complexity = 8,
            risk = 7,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        // Create proposals
        val proposal1 = Proposal(
            id = ProposalId("prop-1"),
            taskId = task.id,
            agentId = AgentId("claude-code"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.85,
            tokenUsage = TokenUsage(inputTokens = 200, outputTokens = 300),
            content = "Approach A",
            createdAt = now
        )
        val proposal2 = Proposal(
            id = ProposalId("prop-2"),
            taskId = task.id,
            agentId = AgentId("codex-cli"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.75,
            tokenUsage = TokenUsage(inputTokens = 180, outputTokens = 280),
            content = "Approach B",
            createdAt = now
        )
        ProposalRepository.insert(proposal1)
        ProposalRepository.insert(proposal2)

        val tool = CompleteTaskTool()
        val result = tool.execute(
            CompleteTaskTool.Params(
                taskId = task.id.value,
                resultSummary = "Consensus reached, using merged approach",
                completedBy = "claude-code",
                decision = CompleteTaskTool.Params.DecisionDTO(
                    considered = listOf(
                        CompleteTaskTool.Params.DecisionDTO.ConsideredDTO(
                            proposalId = proposal1.id.value,
                            agentId = proposal1.agentId.value,
                            inputType = proposal1.inputType.name,
                            confidence = proposal1.confidence,
                            tokenUsage = CompleteTaskTool.Params.TokenMetricsDTO(200, 300)
                        ),
                        CompleteTaskTool.Params.DecisionDTO.ConsideredDTO(
                            proposalId = proposal2.id.value,
                            agentId = proposal2.agentId.value,
                            inputType = proposal2.inputType.name,
                            confidence = proposal2.confidence,
                            tokenUsage = CompleteTaskTool.Params.TokenMetricsDTO(180, 280)
                        )
                    ),
                    selected = listOf(proposal1.id.value, proposal2.id.value),
                    winnerProposalId = proposal1.id.value,
                    agreementRate = 0.9,
                    rationale = "Both proposals had complementary strengths, merged for best result",
                    metadata = mapOf("strategy" to "merge")
                )
            )
        )

        assertEquals(task.id.value, result.taskId)
        assertEquals("COMPLETED", result.status)
        assertNotNull(result.decisionId)
        assertTrue(result.warnings.isEmpty())

        // Verify decision was recorded
        assertTrue(result.recordedMetrics.containsKey("decision.token_savings.absolute"))
        assertTrue(result.recordedMetrics.containsKey("decision.token_savings.percent"))

        // Verify decision in database
        val decision = DecisionRepository.findById(DecisionId(result.decisionId!!))
        assertNotNull(decision)
        assertEquals(2, decision.considered.size)
        assertEquals(2, decision.selected.size)
        assertEquals(proposal1.id, decision.winnerProposalId)
        assertEquals(0.9, decision.agreementRate)
    }

    @Test
    fun throws_when_consensus_task_missing_decision() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-cons-no-dec"),
            title = "Consensus task",
            description = "Needs decision",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code"), AgentId("codex-cli")),
            dependencies = emptySet(),
            complexity = 8,
            risk = 7,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = task.id.value,
                    resultSummary = "Done",
                    completedBy = "claude-code"
                    // Missing decision!
                )
            )
        }
        assertTrue(ex.message!!.contains("CONSENSUS"))
        assertTrue(ex.message!!.contains("no decision payload"))
    }

    @Test
    fun throws_when_completing_already_completed_task() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-already-done"),
            title = "Already done",
            description = "Test",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.COMPLETED,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = task.id.value,
                    resultSummary = "Done again",
                    completedBy = "claude-code"
                )
            )
        }
        assertTrue(ex.message!!.contains("already COMPLETED"))
    }

    @Test
    fun throws_when_proposal_not_found() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-bad-prop"),
            title = "Bad proposal ref",
            description = "Test",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = task.id.value,
                    completedBy = "claude-code",
                    decision = CompleteTaskTool.Params.DecisionDTO(
                        considered = listOf(
                            CompleteTaskTool.Params.DecisionDTO.ConsideredDTO(
                                proposalId = "nonexistent-prop",
                                agentId = "claude-code",
                                inputType = "ARCHITECTURAL_PLAN",
                                confidence = 0.8,
                                tokenUsage = CompleteTaskTool.Params.TokenMetricsDTO(100, 200)
                            )
                        )
                    )
                )
            )
        }
        assertTrue(ex.message!!.contains("does not exist"))
    }

    @Test
    fun throws_when_proposal_belongs_to_different_task() {
        val now = Instant.now()
        val task1 = Task(
            id = TaskId("task-1"),
            title = "Task 1",
            description = "Test",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = now
        )
        val task2 = Task(
            id = TaskId("task-2"),
            title = "Task 2",
            description = "Test",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val proposal = Proposal(
            id = ProposalId("prop-for-task2"),
            taskId = task2.id,
            agentId = AgentId("claude-code"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.8,
            tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 200),
            content = "Plan",
            createdAt = now
        )
        ProposalRepository.insert(proposal)

        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = task1.id.value,
                    completedBy = "claude-code",
                    decision = CompleteTaskTool.Params.DecisionDTO(
                        considered = listOf(
                            CompleteTaskTool.Params.DecisionDTO.ConsideredDTO(
                                proposalId = proposal.id.value,
                                agentId = "claude-code",
                                inputType = "ARCHITECTURAL_PLAN",
                                confidence = 0.8,
                                tokenUsage = CompleteTaskTool.Params.TokenMetricsDTO(100, 200)
                            )
                        )
                    )
                )
            )
        }
        assertTrue(ex.message!!.contains("belongs to task"))
        assertTrue(ex.message!!.contains(task2.id.value))
    }

    @Test
    fun validates_confidence_range() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-bad-confidence"),
            title = "Bad confidence",
            description = "Test",
            type = TaskType.ARCHITECTURE,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val proposal = Proposal(
            id = ProposalId("prop-1"),
            taskId = task.id,
            agentId = AgentId("claude-code"),
            inputType = InputType.ARCHITECTURAL_PLAN,
            confidence = 0.8,
            tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 200),
            content = "Plan",
            createdAt = now
        )
        ProposalRepository.insert(proposal)

        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = task.id.value,
                    completedBy = "claude-code",
                    decision = CompleteTaskTool.Params.DecisionDTO(
                        considered = listOf(
                            CompleteTaskTool.Params.DecisionDTO.ConsideredDTO(
                                proposalId = proposal.id.value,
                                agentId = "claude-code",
                                inputType = "ARCHITECTURAL_PLAN",
                                confidence = 1.5, // Invalid!
                                tokenUsage = CompleteTaskTool.Params.TokenMetricsDTO(100, 200)
                            )
                        )
                    )
                )
            )
        }
        assertTrue(ex.message!!.contains("Confidence"))
        assertTrue(ex.message!!.contains("[0.0, 1.0]"))
    }

    @Test
    fun stores_artifacts_and_snapshots() {
        val now = Instant.now()
        val task = Task(
            id = TaskId("task-with-artifacts"),
            title = "Task with artifacts",
            description = "Test",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("claude-code")),
            dependencies = emptySet(),
            complexity = 3,
            risk = 2,
            createdAt = now,
            updatedAt = now
        )
        TaskRepository.insert(task)

        val tool = CompleteTaskTool()
        val result = tool.execute(
            CompleteTaskTool.Params(
                taskId = task.id.value,
                resultSummary = "Completed with artifacts",
                completedBy = "claude-code",
                artifacts = mapOf(
                    "files_modified" to listOf("file1.kt", "file2.kt"),
                    "tests_added" to 5
                ),
                snapshots = listOf(
                    CompleteTaskTool.Params.SnapshotDTO(
                        label = "before_state",
                        payload = mapOf("count" to 10)
                    ),
                    CompleteTaskTool.Params.SnapshotDTO(
                        label = "after_state",
                        payload = mapOf("count" to 15)
                    )
                )
            )
        )

        assertEquals("COMPLETED", result.status)
        assertEquals(3, result.snapshotIds.size) // 1 artifacts + 2 snapshots

        // Verify snapshot IDs were returned
        assertTrue(result.snapshotIds.all { it > 0 })
    }

    @Test
    fun throws_for_nonexistent_task() {
        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = "no-such-task",
                    completedBy = "claude-code"
                )
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test
    fun throws_for_blank_task_id() {
        val tool = CompleteTaskTool()
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CompleteTaskTool.Params(
                    taskId = "   ",
                    completedBy = "claude-code"
                )
            )
        }
        assertTrue(ex.message!!.contains("cannot be blank"))
    }
}
