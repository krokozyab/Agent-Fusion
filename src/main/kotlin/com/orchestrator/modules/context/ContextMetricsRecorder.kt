package com.orchestrator.modules.context

import com.orchestrator.modules.context.ContextRetrievalModule.TaskContext
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import java.time.Duration

/**
 * SPI that allows context retrieval to emit telemetry without depending on a concrete implementation.
 */
fun interface ContextMetricsRecorder {
    fun record(task: Task, agentId: AgentId, context: TaskContext, duration: Duration)
}
