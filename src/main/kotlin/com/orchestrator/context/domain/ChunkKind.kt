package com.orchestrator.context.domain

enum class ChunkKind {
    CODE_HEADER,
    CODE_CLASS,
    CODE_INTERFACE,
    CODE_ENUM,
    CODE_METHOD,
    CODE_CONSTRUCTOR,
    CODE_BLOCK,
    DOC_COMMENT,
    PARAGRAPH,
    CODE_FUNCTION,
    MARKDOWN_SECTION,
    YAML_BLOCK,
    JSON_BLOCK,
    SQL_STATEMENT,
    DOCSTRING,
    COMMENT
}
