package com.orchestrator.context.chunking

import java.nio.file.Path

object ChunkerRegistry {
    
    private val registry = mapOf(
        "md" to MarkdownChunker(),
        "py" to PythonChunker(),
        "ts" to TypeScriptChunker(),
        "tsx" to TypeScriptChunker(),
        "js" to TypeScriptChunker(),
        "jsx" to TypeScriptChunker(),
        "java" to SimpleChunkerAdapter(JavaChunker()),
        "cs" to SimpleChunkerAdapter(CSharpChunker()),
        "kt" to SimpleChunkerAdapter(KotlinChunker()),
        "yaml" to SimpleChunkerAdapter(YamlChunker()),
        "yml" to SimpleChunkerAdapter(YamlChunker()),
        "sql" to SimpleChunkerAdapter(SqlChunker())
    )
    
    fun getChunker(filePath: Path): Chunker {
        val extension = filePath.fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()
        
        return registry[extension] ?: PlainTextChunker()
    }
    
    fun getChunker(extension: String): Chunker {
        return registry[extension.lowercase().removePrefix(".")] ?: PlainTextChunker()
    }
    
    fun getSupportedExtensions(): Set<String> {
        return registry.keys
    }
    
    fun isSupported(filePath: Path): Boolean {
        val extension = filePath.fileName.toString()
            .substringAfterLast('.', "")
            .lowercase()
        return registry.containsKey(extension)
    }
    
    fun isSupported(extension: String): Boolean {
        return registry.containsKey(extension.lowercase().removePrefix("."))
    }
}

interface SimpleChunker {
    fun chunk(content: String, filePath: String): List<com.orchestrator.context.domain.Chunk>
}

private class SimpleChunkerAdapter(private val simpleChunker: SimpleChunker) : Chunker {
    override val strategy = ChunkingStrategy(
        id = simpleChunker::class.simpleName ?: "unknown",
        displayName = simpleChunker::class.simpleName ?: "Unknown"
    )
    
    override fun chunk(content: String, filePath: String, language: String) = 
        simpleChunker.chunk(content, filePath)
    
    override fun estimateTokens(text: String) = text.length / 4
}
