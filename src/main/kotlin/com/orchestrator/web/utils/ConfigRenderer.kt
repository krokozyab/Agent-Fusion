package com.orchestrator.web.utils

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Utility for rendering configuration objects using reflection.
 * Automatically extracts and formats all properties from data classes.
 */
object ConfigRenderer {

    data class ConfigProperty(
        val name: String,
        val value: Any?,
        val type: String,
        val isNested: Boolean = false
    )

    /**
     * Extract all properties from a configuration object using reflection.
     */
    fun extractProperties(obj: Any): List<ConfigProperty> {
        val kClass = obj::class
        return kClass.memberProperties
            .filter { !shouldSkipProperty(it.name) }
            .mapNotNull { prop ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val property = prop as KProperty1<Any, *>
                    val value = property.get(obj)
                    ConfigProperty(
                        name = prop.name,
                        value = value,
                        type = prop.returnType.toString(),
                        isNested = isNestedObject(value)
                    )
                } catch (e: Exception) {
                    // Skip properties that can't be accessed (e.g., package-private fields)
                    null
                }
            }
            .sortedBy { it.name }
    }

    /**
     * Format a value for display based on its type.
     */
    fun formatValue(value: Any?): String = when (value) {
        null -> "<span class=\"text-muted\">not set</span>"
        is Boolean -> if (value) "✓ true" else "✗ false"
        is String -> if (value.isEmpty()) "<span class=\"text-muted\">empty</span>" else value
        is Number -> formatNumber(value)
        is Enum<*> -> value.name
        is List<*> -> formatList(value)
        is Map<*, *> -> formatMap(value)
        else -> value.toString()
    }

    /**
     * Format a number with appropriate units.
     */
    private fun formatNumber(num: Number): String {
        val value = num.toLong()
        return when {
            value >= 1_000_000 -> "${value / 1_000_000}M"
            value >= 1_000 -> "${value / 1_000}K"
            else -> value.toString()
        }
    }

    /**
     * Format a list for display.
     */
    private fun formatList(list: List<*>): String {
        if (list.isEmpty()) return "<span class=\"text-muted\">empty</span>"
        
        val items = list.take(50)
        val formatted = items.joinToString(", ") { it?.toString() ?: "null" }
        
        return if (list.size > 50) {
            "$formatted <span class=\"text-muted\">... and ${list.size - 50} more</span>"
        } else {
            formatted
        }
    }

    /**
     * Format a map for display.
     */
    private fun formatMap(map: Map<*, *>): String {
        if (map.isEmpty()) return "<span class=\"text-muted\">empty</span>"
        
        val entries = map.entries.take(50)
        val formatted = entries.joinToString(", ") { (k, v) -> 
            "$k: ${v?.toString() ?: "null"}" 
        }
        
        return if (map.size > 50) {
            "$formatted <span class=\"text-muted\">... and ${map.size - 50} more</span>"
        } else {
            formatted
        }
    }

    /**
     * Check if a value is a nested configuration object (data class).
     */
    private fun isNestedObject(value: Any?): Boolean {
        if (value == null) return false
        if (isPrimitiveType(value)) return false
        return try {
            val kClass = value::class
            kClass.isData
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a value is a primitive type or collection.
     */
    private fun isPrimitiveType(value: Any): Boolean = when (value) {
        is String, is Number, is Boolean, is Enum<*>, is List<*>, is Map<*,*> -> true
        else -> false
    }

    /**
     * Determine if a property should be skipped (computed properties).
     */
    private fun shouldSkipProperty(name: String): Boolean {
        return name in setOf("enabledProviders") // Skip computed properties
    }

    /**
     * Check if a value contains sensitive data that should be masked.
     */
    fun isSensitive(name: String): Boolean {
        val lowerName = name.lowercase()
        return lowerName.contains("password") || 
               lowerName.contains("secret") || 
               lowerName.contains("token") ||
               lowerName.contains("key")
    }

    /**
     * Mask sensitive values.
     */
    fun maskSensitive(value: String): String {
        return "********"
    }
}
