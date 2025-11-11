package com.orchestrator.context.domain

/**
 * High-level categories for source code symbols extracted during indexing.
 *
 * The enum intentionally favours semantic groupings that align with the context
 * providers so that ranking heuristics can treat related constructs similarly.
 */
enum class SymbolType {
    PACKAGE,
    IMPORT,
    CLASS,
    INTERFACE,
    ENUM,
    FUNCTION,
    METHOD,
    PROPERTY,
    VARIABLE,
    CONSTANT
}
