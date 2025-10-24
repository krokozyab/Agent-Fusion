package com.orchestrator.web.dto

data class FileStateDTO(
    val path: String,
    val status: String,
    val sizeBytes: Long,
    val lastModified: String?,
    val chunkCount: Int
)
