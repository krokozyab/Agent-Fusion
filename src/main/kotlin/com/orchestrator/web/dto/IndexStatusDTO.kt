package com.orchestrator.web.dto

data class IndexStatusDTO(
    val totalFiles: Int,
    val indexedFiles: Int,
    val pendingFiles: Int,
    val failedFiles: Int,
    val lastRefresh: String?,
    val health: String,
    val files: List<FileStateDTO>,
    val filesystem: FilesystemStatusDTO?,
    val neo4jEnabled: Boolean = false,
    val neo4jConnected: Boolean = false,
    val totalClasses: Int = 0,
    val totalMethods: Int = 0,
    val totalSections: Int = 0,
    val orphanedChunks: Int = 0
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
