package com.orchestrator.context.neo4j

data class CodeStructure(
    val filePath: String,
    val language: String,
    val classes: List<ClassNode>,
    val functions: List<FunctionNode>,
    val imports: List<ImportNode>
)

data class ClassNode(
    val id: String,
    val name: String,
    val qualifiedName: String?,
    val startLine: Int,
    val endLine: Int,
    val methods: List<MethodNode>,
    val fields: List<FieldNode>
)

data class MethodNode(
    val id: String,
    val name: String,
    val signature: String,
    val startLine: Int,
    val endLine: Int,
    val parameters: List<ParameterNode>,
    val returnType: String?
)

data class FunctionNode(
    val id: String,
    val name: String,
    val signature: String,
    val startLine: Int,
    val endLine: Int,
    val parameters: List<ParameterNode>,
    val returnType: String?
)

data class FieldNode(
    val name: String,
    val type: String?,
    val startLine: Int
)

data class ParameterNode(
    val name: String,
    val type: String?
)

data class ImportNode(
    val path: String,
    val alias: String?
)
