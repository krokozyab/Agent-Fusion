package com.orchestrator.web.dto

data class IndexStatusDTO(
    val totalFiles: Int,
    val indexedFiles: Int,
    val pendingFiles: Int,
    val failedFiles: Int,
    val lastRefresh: String?,
    val health: String,
    val files: List<FileStateDTO>
)
