package com.orchestrator.web.utils

/**
 * Utility for formatting objects as pretty-printed JSON-like strings
 * with proper indentation to avoid horizontal scrolling
 */
object JsonFormatter {
    fun format(value: Any?, indent: Int = 0): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Map<*, *> -> formatMap(value as Map<String, Any?>, indent)
            is List<*> -> formatList(value, indent)
            else -> value.toString()
        }
    }

    private fun formatMap(map: Map<String, Any?>, indent: Int): String {
        if (map.isEmpty()) return "{}"

        val currentIndent = " ".repeat(indent)
        val nextIndent = " ".repeat(indent + 2)

        val entries = map.entries.joinToString(",\n$nextIndent") { (key, value) ->
            "\"$key\": ${format(value, indent + 2)}"
        }

        return "{\n$nextIndent$entries\n$currentIndent}"
    }

    private fun formatList(list: List<*>, indent: Int): String {
        if (list.isEmpty()) return "[]"

        val currentIndent = " ".repeat(indent)
        val nextIndent = " ".repeat(indent + 2)

        val items = list.joinToString(",\n$nextIndent") { item ->
            format(item, indent + 2)
        }

        return "[\n$nextIndent$items\n$currentIndent]"
    }
}
