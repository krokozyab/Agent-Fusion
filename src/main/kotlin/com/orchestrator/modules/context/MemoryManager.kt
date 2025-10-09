package com.orchestrator.modules.context

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import com.orchestrator.storage.repositories.MessageRepository
import com.orchestrator.utils.TokenEstimator
import java.time.Instant

/**
 * MemoryManager: manages conversation history for a task.
 * - Stores messages efficiently in DuckDB via MessageRepository
 * - Retrieves history by task ID
 * - Summarizes context on retrieval if token limit is exceeded
 * - Prunes oldest messages to respect storage limits
 */
object MemoryManager {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL, SUMMARY }

    data class ConversationMessage(
        val id: Long? = null,
        val taskId: TaskId,
        val role: Role,
        val content: String,
        val agentId: AgentId? = null,
        val tokens: Int = 0,
        val ts: Instant = Instant.now(),
        val metadataJson: String? = null
    )

    /** Append a message to storage, estimating tokens if not provided. */
    fun appendMessage(
        taskId: TaskId,
        role: Role,
        content: String,
        agentId: AgentId? = null,
        tokens: Int? = null,
        metadataJson: String? = null,
        ts: Instant = Instant.now()
    ): Long {
        val tok = tokens ?: TokenEstimator.estimateTokens(content)
        return MessageRepository.insert(
            taskId = taskId,
            role = role.name.lowercase(),
            content = content,
            tokens = tok,
            agentId = agentId,
            metadataJson = metadataJson,
            ts = ts
        )
    }

    /** Retrieve conversation history. If tokens exceed maxTokens, return a summarized view. */
    fun getHistory(taskId: TaskId, maxTokens: Int = 4000): List<ConversationMessage> {
        val rows = MessageRepository.listByTask(taskId)
        if (rows.isEmpty()) return emptyList()
        val total = rows.sumOf { it.tokens }
        val messages = rows.map {
            ConversationMessage(
                id = it.id,
                taskId = it.taskId,
                role = parseRole(it.role),
                content = it.content,
                agentId = it.agentId,
                tokens = it.tokens,
                ts = it.ts,
                metadataJson = it.metadataJson
            )
        }
        if (total <= maxTokens) return messages
        return summarizeToFit(messages, maxTokens)
    }

    /** Delete oldest persisted messages until total tokens are under maxTokens. */
    fun pruneByTokens(taskId: TaskId, maxTokens: Int): Int {
        require(maxTokens >= 0) { "maxTokens must be >= 0" }
        return MessageRepository.deleteOldestBeyondTokens(taskId, maxTokens)
    }

    /** Compute current persisted token total for a task. */
    fun currentTokenTotal(taskId: TaskId): Int = MessageRepository.countTokens(taskId)

    private fun parseRole(role: String): Role = try {
        Role.valueOf(role.uppercase())
    } catch (_: Exception) {
        Role.USER
    }

    // Summarization strategy: keep the most recent messages intact, and compress the oldest block
    // into one SUMMARY message containing bullet points. We iterate until the token total fits.
    private fun summarizeToFit(messages: List<ConversationMessage>, maxTokens: Int): List<ConversationMessage> {
        if (messages.isEmpty()) return messages
        var working = messages.toMutableList()
        var total = working.sumOf { it.tokens }
        if (total <= maxTokens) return working

        // We'll progressively summarize the oldest 25-40% chunk until within limit or until only minimal context remains.
        while (total > maxTokens && working.size > 3) {
            val chunkSize = (working.size * 0.35).toInt().coerceAtLeast(1)
            val oldestChunk = working.take(chunkSize)
            val rest = working.drop(chunkSize)
            val summary = summarizeChunk(oldestChunk)
            val summaryTokens = TokenEstimator.estimateTokens(summary)
            val summarizedMsg = ConversationMessage(
                id = null,
                taskId = oldestChunk.first().taskId,
                role = Role.SUMMARY,
                content = summary,
                agentId = null,
                tokens = summaryTokens,
                ts = oldestChunk.last().ts // keep chronological order
            )
            working = (listOf(summarizedMsg) + rest).toMutableList()
            total = working.sumOf { it.tokens }
            // Safety: if summarization didn't reduce tokens (pathological), break to avoid loop
            if (total >= messages.sumOf { it.tokens }) break
        }
        // If still too large, hard-truncate oldest while keeping head summary and newest messages
        while (working.sumOf { it.tokens } > maxTokens && working.size > 1) {
            working.removeAt(0)
        }
        return working
    }

    private fun summarizeChunk(chunk: List<ConversationMessage>): String {
        // Very lightweight heuristic summary: convert messages into bullet lines with role prefixes
        val sb = StringBuilder()
        sb.append("Summary of ").append(chunk.size).append(" messages:\n")
        for (msg in chunk.take(20)) { // limit bullets for efficiency
            val role = when (msg.role) {
                Role.USER -> "User"
                Role.ASSISTANT -> "Assistant"
                Role.SYSTEM -> "System"
                Role.TOOL -> "Tool"
                Role.SUMMARY -> "Summary"
            }
            val text = msg.content.replace('\n', ' ').take(240)
            sb.append("- ").append(role).append(": ").append(text).append('\n')
        }
        if (chunk.size > 20) sb.append("- ... ")
        sb.append("\nKey points: consolidate user intents, decisions, and action items above.")
        return sb.toString().trim()
    }
}
