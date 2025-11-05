package com.orchestrator.web.dto

data class IndexStatusDTO(
    val totalFiles: Int,
    val indexedFiles: Int,
    val pendingFiles: Int,
    val failedFiles: Int,
    val lastRefresh: String?,
    val health: String,
    val files: List<FileStateDTO>,
    val filesystem: FilesystemStatusDTO?
)

data class FilesystemStatusDTO(
    val totalFiles: Int,
    val roots: List<FilesystemRootDTO>,
    val watchRoots: List<String>,
    val scannedAt: String?,
    val missingFromCatalog: List<String>,
    val orphanedInCatalog: List<String>,
    val missingTotal: Int,
    val orphanedTotal: Int
)

data class FilesystemRootDTO(
    val path: String,
    val totalFiles: Int
)
