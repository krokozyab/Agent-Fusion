package com.orchestrator.web.dto

data class TaskListDTO(
    val items: List<TaskDTO>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val sort: SortInfo? = null,
    val filters: Map<String, String> = emptyMap()
) {
    data class SortInfo(
        val field: String,
        val direction: Direction
    ) {
        enum class Direction { ASC, DESC }
    }
}
