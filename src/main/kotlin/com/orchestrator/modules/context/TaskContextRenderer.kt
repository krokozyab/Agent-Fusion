package com.orchestrator.modules.context

import com.orchestrator.context.domain.ContextSnippet
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

object TaskContextRenderer {

    data class Statistics(
        val snippetCount: Int,
        val fileCount: Int,
        val estimatedTokens: Int,
        val byteSize: Int
    )

    fun render(context: ContextRetrievalModule.TaskContext, maxTokens: Int? = null): String =
        renderInternal(context, maxTokens, pretty = true)

    fun renderCompact(context: ContextRetrievalModule.TaskContext, maxTokens: Int? = null): String =
        renderInternal(context, maxTokens, pretty = false)

    fun validateXml(xml: String): Boolean = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        true
    }.getOrDefault(false)

    fun getStatistics(xml: String): Statistics = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        val snippetNodes = document.getElementsByTagName("snippet")
        val fileNodes = document.getElementsByTagName("file")
        val estimatedTokens = (0 until snippetNodes.length).sumOf { index ->
            val snippet = snippetNodes.item(index)
            val tokenEstimate = snippet.attributes?.getNamedItem("token_estimate")?.nodeValue?.toIntOrNull()
            tokenEstimate ?: snippet.textContent.length / 4
        }
        Statistics(
            snippetCount = snippetNodes.length,
            fileCount = fileNodes.length,
            estimatedTokens = estimatedTokens,
            byteSize = xml.toByteArray(StandardCharsets.UTF_8).size
        )
    }.getOrElse {
        Statistics(0, 0, 0, xml.toByteArray(StandardCharsets.UTF_8).size)
    }

    private fun renderInternal(
        context: ContextRetrievalModule.TaskContext,
        maxTokens: Int?,
        pretty: Boolean
    ): String {
        val newline = if (pretty) "\n" else ""
        val indent = if (pretty) "  " else ""

        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append(newline)

        val attributes = mutableListOf("task_id" to context.taskId)
        if (context.diagnostics.fallbackUsed) {
            context.diagnostics.fallbackProvider?.let { attributes += "fallback_provider" to it }
        }

        builder.append("<project_context")
        attributes.forEach { (key, value) ->
            builder.append(" ").append(key).append("=\"").append(escapeAttribute(value)).append("\"")
        }
        builder.append(">").append(newline)

        if (context.metadata.isNotEmpty()) {
            builder.append(indent).append("<metadata>").append(newline)
            context.metadata.forEach { (key, value) ->
                builder.append(indent).append(indent)
                    .append("<").append(sanitiseKey(key)).append(">")
                    .append(escapeText(value))
                    .append("</").append(sanitiseKey(key)).append(">")
                    .append(newline)
            }
            builder.append(indent).append("</metadata>").append(newline)
        }

        val orderedSnippets = context.snippets
            .sortedWith(compareByDescending<ContextSnippet> { it.score }.thenBy { it.filePath })

        val limited = applyBudget(orderedSnippets, context.diagnostics.tokensRequested, maxTokens)
        val truncated = limited.size < orderedSnippets.size

        val grouped = limited.groupBy { it.filePath }
        builder.append(indent).append("<files>").append(newline)
        grouped.forEach { (path, snippets) ->
            val language = snippets.firstNotNullOfOrNull { it.language }.orEmpty()
            builder.append(indent).append(indent)
                .append("<file path=\"").append(escapeAttribute(path))
                .append("\"")
            if (language.isNotBlank()) {
                builder.append(" language=\"").append(escapeAttribute(language)).append("\"")
            }
            builder.append(">").append(newline)

            snippets.sortedByDescending { it.score }.forEach { snippet ->
                builder.append(indent).append(indent).append(indent)
                    .append("<snippet")
                    .append(" label=\"").append(escapeAttribute(snippet.label ?: ""))
                    .append("\" kind=\"").append(snippet.kind.name)
                    .append("\" score=\"").append(String.format(Locale.US, "%.3f", snippet.score))
                snippet.offsets?.let { range ->
                    builder.append("\" lines=\"").append(range.first).append("-").append(range.last)
                }
                snippet.metadata["token_estimate"]?.let {
                    builder.append("\" token_estimate=\"").append(it)
                }
                builder.append("\">").append(newline)

                builder.append(indent).append(indent).append(indent).append(indent)
                    .append("<content><![CDATA[").append(snippet.text).append("]]></content>")
                    .append(newline)

                if (snippet.metadata.isNotEmpty()) {
                    builder.append(indent).append(indent).append(indent).append("<metadata>").append(newline)
                    snippet.metadata.forEach { (key, value) ->
                        builder.append(indent).append(indent).append(indent).append(indent)
                            .append("<").append(sanitiseKey(key)).append(">")
                            .append(escapeText(value))
                            .append("</").append(sanitiseKey(key)).append(">")
                            .append(newline)
                    }
                    builder.append(indent).append(indent).append(indent).append("</metadata>").append(newline)
                }

                builder.append(indent).append(indent).append(indent).append("</snippet>").append(newline)
            }

            builder.append(indent).append(indent).append("</file>").append(newline)
        }
        builder.append(indent).append("</files>").append(newline)

        if (truncated) {
            builder.append(indent).append("<!-- Context truncated due to token budget -->").append(newline)
        }

        builder.append(indent).append("<diagnostics>").append(newline)
        builder.append(indent).append(indent).append("<total_snippets>")
            .append(limited.size)
            .append("</total_snippets>").append(newline)
        builder.append(indent).append(indent).append("<tokens_requested>")
            .append(context.diagnostics.tokensRequested)
            .append("</tokens_requested>").append(newline)
        builder.append(indent).append(indent).append("<tokens_used>")
            .append(context.diagnostics.tokensUsed)
            .append("</tokens_used>").append(newline)
        builder.append(indent).append(indent).append("<duration_ms>")
            .append(context.diagnostics.duration.toMillis())
            .append("</duration_ms>").append(newline)
        if (context.diagnostics.warnings.isNotEmpty()) {
            builder.append(indent).append(indent).append("<warnings>").append(newline)
            context.diagnostics.warnings.forEach { warning ->
                builder.append(indent).append(indent).append(indent)
                    .append("<warning>").append(escapeText(warning)).append("</warning>").append(newline)
            }
            builder.append(indent).append(indent).append("</warnings>").append(newline)
        }
        builder.append(indent).append("</diagnostics>").append(newline)

        builder.append("</project_context>")

        return if (pretty) builder.toString() else builder.toString().replace("\n", "")
    }

    private data class BudgetResult(val snippets: List<ContextSnippet>)

    private fun applyBudget(
        snippets: List<ContextSnippet>,
        requestedTokens: Int,
        maxTokens: Int?
    ): List<ContextSnippet> {
        val budget = maxTokens ?: requestedTokens.coerceAtLeast(0)
        if (budget <= 0) return emptyList()

        val result = mutableListOf<ContextSnippet>()
        var tokensUsed = 0

        for (snippet in snippets) {
            val tokenEstimate = snippet.metadata["token_estimate"]?.toIntOrNull() ?: snippet.text.length / 4
            if (tokensUsed + tokenEstimate > budget) break
            tokensUsed += tokenEstimate
            result += snippet
        }
        return result
    }

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun escapeText(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun sanitiseKey(key: String): String {
        var sanitized = key.trim()
        if (sanitized.isEmpty()) return "entry"
        sanitized = sanitized.replace(" ", "_")
        sanitized = sanitized.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (sanitized.first().isDigit()) {
            sanitized = "_$sanitized"
        }
        return sanitized
    }
}
