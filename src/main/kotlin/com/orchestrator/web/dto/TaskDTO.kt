package com.orchestrator.web.dto

data class TaskDTO(
    val id: String,
    val title: String,
    val description: String? = null,
    val type: String,
    val status: String,
    val routing: String,
    val assigneeIds: List<String>,
    val dependencyIds: List<String>,
    val complexity: Int,
    val risk: Int,
    val createdAt: String,
    val updatedAt: String? = null,
    val dueAt: String? = null,
    val age: String,
    val duration: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
