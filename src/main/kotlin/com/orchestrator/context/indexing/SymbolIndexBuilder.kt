package com.orchestrator.context.indexing

import com.orchestrator.context.domain.SymbolRecord
import com.orchestrator.context.domain.SymbolType
import com.orchestrator.context.storage.SymbolRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlin.math.max

/**
 * Lightweight symbol extractor that uses heuristics to capture commonly referenced identifiers.
 *
 * The implementation intentionally favours recall over perfect precision; providers apply their own
 * ranking and filtering. Tree-sitter integration can replace this module later without changing callers.
 */
class SymbolIndexBuilder(
    private val repository: SymbolRepository = SymbolRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    suspend fun indexFile(path: Path, fileId: Long, language: String): List<SymbolRecord> {
        require(Files.exists(path)) { "File does not exist: $path" }
        val code = Files.readString(path)
        if (code.isBlank()) {
            repository.replaceForFile(fileId, emptyList())
            return emptyList()
        }

        val languageKey = language.lowercase(Locale.US)
        val extracted = when (languageKey) {
            "kotlin", "kt" -> extractKotlin(code, fileId, languageKey)
            "java" -> extractJava(code, fileId, languageKey)
            "python", "py" -> extractPython(code, path, fileId, languageKey)
            "typescript", "ts", "tsx", "javascript", "js", "jsx" -> extractTypeScript(code, fileId, languageKey)
            else -> extractPlainSymbols(code, path, fileId, languageKey)
        }

        val timestamp = Instant.now(clock)
        val normalised = extracted.map { it.copy(createdAt = timestamp) }
        return repository.replaceForFile(fileId, normalised)
    }

    private fun extractKotlin(code: String, fileId: Long, language: String): List<SymbolRecord> {
        val lines = code.lines()
        val packageName = lines.firstNotNullOfOrNull { packageRegex.find(it)?.groupValues?.getOrNull(1) }

        val symbols = mutableListOf<SymbolRecord>()
        val classStack = ArrayDeque<String>()
        var lineNumber = 0

        for (line in lines) {
            lineNumber++
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            importRegex.matchEntire(trimmed)?.let { match ->
                val fqName = match.groupValues[1]
                val name = fqName.substringAfterLast('.')
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.IMPORT,
                    name = name,
                    qualified = fqName,
                    signature = "import $fqName",
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            classRegex.find(trimmed)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                classStack.addLast(name)
                val qualified = listOfNotNull(packageName, name).joinToString(".")
                symbols += symbol(
                    fileId = fileId,
                    type = when (kind) {
                        "interface" -> SymbolType.INTERFACE
                        "enum" -> SymbolType.ENUM
                        else -> SymbolType.CLASS
                    },
                    name = name,
                    qualified = qualified.ifBlank { name },
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            funRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[1]
                val qualified = buildQualified(packageName, classStack, name)
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.FUNCTION,
                    name = name,
                    qualified = qualified,
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            propertyRegex.find(trimmed)?.let { match ->
                val name = match.groupValues[2]
                val qualified = buildQualified(packageName, classStack, name)
                val type = if (match.groupValues[1] == "val") SymbolType.PROPERTY else SymbolType.VARIABLE
                symbols += symbol(
                    fileId = fileId,
                    type = type,
                    name = name,
                    qualified = qualified,
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            // Handle scope exit
            if (trimmed.contains('}')) {
                val closures = max(1, trimmed.count { it == '}' })
                repeat(closures.coerceAtMost(classStack.size)) { classStack.removeLastOrNull() }
            }
        }

        return symbols
    }

    private fun extractJava(code: String, fileId: Long, language: String): List<SymbolRecord> {
        val lines = code.lines()
        val packageName = lines.firstNotNullOfOrNull { javaPackageRegex.find(it)?.groupValues?.getOrNull(1) }
        val symbols = mutableListOf<SymbolRecord>()
        val classStack = ArrayDeque<String>()
        var lineNumber = 0

        for (line in lines) {
            lineNumber++
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            importRegex.matchEntire(trimmed)?.let { match ->
                val fqName = match.groupValues[1]
                val simple = fqName.substringAfterLast('.')
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.IMPORT,
                    name = simple,
                    qualified = fqName,
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            javaClassRegex.matchEntire(trimmed)?.let { match ->
                val modifier = match.groupValues[1]
                val kind = match.groupValues[2]
                val name = match.groupValues[3]
                classStack.addLast(name)
                val qualified = listOfNotNull(packageName, classStack.joinToString(".")).filter { it.isNotBlank() }.joinToString(".")
                val type = when (kind) {
                    "interface" -> SymbolType.INTERFACE
                    "enum" -> SymbolType.ENUM
                    else -> SymbolType.CLASS
                }
                symbols += symbol(
                    fileId = fileId,
                    type = type,
                    name = name,
                    qualified = qualified,
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            javaMethodRegex.matchEntire(trimmed)?.let { match ->
                val name = match.groupValues[2]
                val qualified = buildQualified(packageName, classStack, name)
                val type = if (classStack.isNotEmpty()) SymbolType.METHOD else SymbolType.FUNCTION
                symbols += symbol(
                    fileId = fileId,
                    type = type,
                    name = name,
                    qualified = qualified,
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            javaFieldRegex.matchEntire(trimmed)?.let { match ->
                val name = match.groupValues[2]
                val qualified = buildQualified(packageName, classStack, name)
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.PROPERTY,
                    name = name,
                    qualified = qualified,
                    signature = trimmed,
                    language = language,
                    startLine = lineNumber
                )
                return@let
            }

            if (trimmed.contains('}')) {
                repeat(trimmed.count { it == '}' }.coerceAtMost(classStack.size)) { classStack.removeLastOrNull() }
            }
        }

        return symbols
    }

    private fun extractPython(code: String, path: Path, fileId: Long, language: String): List<SymbolRecord> {
        val moduleName = path.fileName.toString().substringBeforeLast('.', path.fileName.toString())
        val moduleQualified = moduleName.replace('/', '.')
        val symbols = mutableListOf<SymbolRecord>()
        val classStack = ArrayDeque<Pair<String, Int>>() // name to indent
        val lines = code.lines()

        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            pythonImportRegex.matchEntire(line)?.let { match ->
                val module = match.groupValues[1]
                val alias = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                val name = alias ?: module.substringAfterLast('.')
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.IMPORT,
                    name = name,
                    qualified = module,
                    signature = rawLine.trim(),
                    language = language,
                    startLine = lineNumber
                )
                return@forEachIndexed
            }

            pythonFromImportRegex.matchEntire(line)?.let { match ->
                val module = match.groupValues[1]
                val imported = match.groupValues[2]
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.IMPORT,
                    name = imported,
                    qualified = "$module.$imported",
                    signature = rawLine.trim(),
                    language = language,
                    startLine = lineNumber
                )
                return@forEachIndexed
            }

            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) rawLine.length else it }
            while (classStack.isNotEmpty() && indent <= classStack.last().second) {
                classStack.removeLastOrNull()
            }

            pythonClassRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                classStack.addLast(name to indent)
                val qualified = buildQualified(moduleQualified.takeIf { it.isNotBlank() }, classStack.map { it.first }, name)
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.CLASS,
                    name = name,
                    qualified = qualified,
                    signature = line,
                    language = language,
                    startLine = lineNumber
                )
                return@forEachIndexed
            }

            pythonFunctionRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                val type = if (classStack.isNotEmpty()) SymbolType.METHOD else SymbolType.FUNCTION
                val qualified = buildQualified(moduleQualified.takeIf { it.isNotBlank() }, classStack.map { it.first }, name)
                symbols += symbol(
                    fileId = fileId,
                    type = type,
                    name = name,
                    qualified = qualified,
                    signature = line,
                    language = language,
                    startLine = lineNumber
                )
                return@forEachIndexed
            }
        }

        return symbols
    }

    private fun extractTypeScript(code: String, fileId: Long, language: String): List<SymbolRecord> {
        val symbols = mutableListOf<SymbolRecord>()
        val lines = code.lines()
        var lineNumber = 0

        lines.forEach { rawLine ->
            lineNumber++
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) return@forEach

            tsImportRegex.matchEntire(line)?.let { match ->
                val bindings = match.groupValues[1]
                val module = match.groupValues[2].removeSurrounding("'", "'")
                bindings.split(',', ' ', '{', '}')
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "from" && it != "as" }
                    .forEach { binding ->
                        symbols += symbol(
                            fileId = fileId,
                            type = SymbolType.IMPORT,
                            name = binding,
                            qualified = "$module::$binding",
                            signature = line,
                            language = language,
                            startLine = lineNumber
                        )
                    }
                return@forEach
            }

            tsInterfaceRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.INTERFACE,
                    name = name,
                    qualified = name,
                    signature = line,
                    language = language,
                    startLine = lineNumber
                )
                return@forEach
            }

            tsClassRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.CLASS,
                    name = name,
                    qualified = name,
                    signature = line,
                    language = language,
                    startLine = lineNumber
                )
                return@forEach
            }

            tsFunctionRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.FUNCTION,
                    name = name,
                    qualified = name,
                    signature = line,
                    language = language,
                    startLine = lineNumber
                )
                return@forEach
            }

            tsConstRegex.find(line)?.let { match ->
                val name = match.groupValues[1]
                symbols += symbol(
                    fileId = fileId,
                    type = SymbolType.VARIABLE,
                    name = name,
                    qualified = name,
                    signature = line,
                    language = language,
                    startLine = lineNumber
                )
            }
        }

        return symbols
    }

    private fun extractPlainSymbols(code: String, path: Path, fileId: Long, language: String): List<SymbolRecord> {
        val baseName = path.fileName.toString().substringBeforeLast('.')
        val simpleRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]{2,})\b""")
        val seen = LinkedHashSet<String>()
        simpleRegex.findAll(code)
            .map { it.groupValues[1] }
            .filter { it != baseName }
            .take(20)
            .forEach { seen += it }

        return seen.mapIndexed { idx, name ->
            symbol(
                fileId = fileId,
                type = SymbolType.FUNCTION,
                name = name,
                qualified = name,
                signature = "symbol $name",
                language = language,
                startLine = idx + 1
            )
        }
    }

    private fun symbol(
        fileId: Long,
        type: SymbolType,
        name: String,
        qualified: String?,
        signature: String?,
        language: String,
        startLine: Int
    ): SymbolRecord = SymbolRecord(
        id = 0,
        fileId = fileId,
        chunkId = null,
        symbolType = type,
        name = name,
        qualifiedName = qualified,
        signature = signature,
        language = language,
        startLine = startLine,
        endLine = startLine,
        createdAt = Instant.EPOCH
    )

    private fun buildQualified(packageName: String?, classStack: Collection<String>, member: String): String? {
        val segments = mutableListOf<String>()
        packageName?.takeIf { it.isNotBlank() }?.let { segments += it }
        if (classStack.isNotEmpty()) segments += classStack
        segments += member
        return segments.joinToString(".").takeIf { it.isNotBlank() }
    }

    companion object {
        private val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""")
        private val javaPackageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)\s*;""")
        private val importRegex = Regex("""^import\s+([A-Za-z0-9_.*]+)""")
        private val classRegex = Regex("""^(class|interface|enum|object)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val funRegex = Regex("""^fun\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val propertyRegex = Regex("""^(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)""")

        private val javaClassRegex = Regex("""^(?:public|protected|private|final|abstract|\s)*\s*(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val javaMethodRegex = Regex("""^(?:public|protected|private|static|final|abstract|\s)+([A-Za-z0-9_<>,\s\[\]]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val javaFieldRegex = Regex("""^(?:public|protected|private|static|final|\s)+([A-Za-z0-9_<>,\s\[\]]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*[=;]""")

        private val pythonImportRegex = Regex("""^import\s+([A-Za-z0-9_.]+)(?:\s+as\s+([A-Za-z0-9_]+))?$""")
        private val pythonFromImportRegex = Regex("""^from\s+([A-Za-z0-9_.]+)\s+import\s+([A-Za-z0-9_]+)""")
        private val pythonClassRegex = Regex("""^class\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val pythonFunctionRegex = Regex("""^def\s+([A-Za-z_][A-Za-z0-9_]*)""")

        private val tsImportRegex = Regex("""^import\s+(.*)\s+from\s+(['"].+['"]);?""")
        private val tsInterfaceRegex = Regex("""^(?:export\s+)?interface\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val tsClassRegex = Regex("""^(?:export\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val tsFunctionRegex = Regex("""^(?:export\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)""")
        private val tsConstRegex = Regex("""^(?:export\s+)?const\s+([A-Za-z_][A-Za-z0-9_]*)""")
    }
}
