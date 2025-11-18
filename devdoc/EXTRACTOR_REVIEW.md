# Existing Extractor Review

## Overview
This document reviews the existing document extractors in the codebase to inform the Neo4j adapter implementation strategy.

---

## Existing Extractors

### 1. PdfDocumentExtractor
**Location**: `src/main/kotlin/com/orchestrator/context/chunking/PdfDocumentExtractor.kt`

**Technology**: Apache PDFBox 2.0.30

**API**:
```kotlin
object PdfDocumentExtractor {
    fun extract(path: Path): String
}
```

**Behavior**:
- Loads PDF using PDFBox's PDDocument
- Uses PDFTextStripper with `sortByPosition = true` to preserve reading order
- Normalizes whitespace: removes carriage returns, null chars, collapses multiple newlines
- Returns plain text with paragraph boundaries preserved

**Quality**: Production-ready, handles multi-page PDFs correctly

---

### 2. WordDocumentExtractor
**Location**: `src/main/kotlin/com/orchestrator/context/chunking/WordDocumentExtractor.kt`

**Technology**: Apache POI 5.2.5

**API**:
```kotlin
object WordDocumentExtractor {
    fun supports(extension: String): Boolean
    fun extract(path: Path, extension: String): String
}
```

**Supported Formats**:
- `.doc` (legacy Word) → HWPFDocument + WordExtractor
- `.docx` (modern Word) → XWPFDocument + XWPFWordExtractor

**Behavior**:
- Detects format by extension
- Extracts plain text using POI extractors
- Normalizes whitespace (same as PDF)
- Returns plain text with paragraph structure

**Quality**: Production-ready, handles both legacy and modern Word formats

---

### 3. MarkdownChunker
**Location**: `src/main/kotlin/com/orchestrator/context/chunking/MarkdownChunker.kt`

**Technology**: Custom parser (no external library)

**API**:
```kotlin
class MarkdownChunker(
    private val maxTokens: Int = 400,
    private val estimator: TokenEstimator = TokenEstimator
) : Chunker {
    override fun chunk(content: String, filePath: String, language: String): List<Chunk>
}
```

**Behavior**:
- Parses markdown by headings (# through ######)
- Detects and preserves fenced code blocks (``` and ~~~)
- Splits content into sections based on heading hierarchy
- Respects token limits, splits large sections at blank lines
- Returns structured chunks with:
  - `ChunkKind.MARKDOWN_SECTION` for text sections
  - `ChunkKind.CODE_BLOCK` for fenced code
  - Line numbers (startLine, endLine)
  - Token estimates
  - Section labels (heading text)

**Quality**: Production-ready, sophisticated chunking with token awareness

---

## Chunk Data Model

### Chunk
```kotlin
data class Chunk(
    val id: Long,
    val fileId: Long,
    val ordinal: Int,
    val kind: ChunkKind,
    val startLine: Int?,
    val endLine: Int?,
    val tokenEstimate: Int?,
    val content: String,
    val summary: String?,
    val createdAt: Instant
)
```

### ChunkKind Enum
Relevant values for documents:
- `PARAGRAPH` - Plain text paragraphs (PDF, Word)
- `MARKDOWN_SECTION` - Markdown sections with headings
- `CODE_BLOCK` - Fenced code blocks in markdown
- `DOC_COMMENT` - Documentation comments
- `COMMENT` - Regular comments

---

## Neo4j Adapter Strategy

### Reuse Approach
**DO NOT rewrite extractors**. Instead, create adapters that:

1. **Call existing extractors** for text content
2. **Parse structure** from extracted text
3. **Map to Neo4j nodes** (Document → Section → Paragraph)

### Adapter Architecture

```kotlin
class DocumentStructureAdapter {
    // Delegates to existing extractors
    fun extractStructure(filePath: Path, fileType: FileType): DocumentStructure? {
        val text = when (fileType) {
            FileType.PDF -> PdfDocumentExtractor.extract(filePath)
            FileType.WORD -> WordDocumentExtractor.extract(filePath, extension)
            FileType.MARKDOWN -> Files.readString(filePath)
            else -> return null
        }
        
        return when (fileType) {
            FileType.PDF, FileType.WORD -> parseParagraphStructure(text)
            FileType.MARKDOWN -> parseMarkdownStructure(text)
            else -> null
        }
    }
}
```

### Structure Parsing

#### For PDF/Word (Paragraph-based)
```kotlin
private fun parseParagraphStructure(text: String): DocumentStructure {
    val paragraphs = text.split("\n\n")
        .filter { it.isNotBlank() }
        .mapIndexed { index, para ->
            Paragraph(
                id = UUID.randomUUID().toString(),
                ordinal = index,
                content = para.trim(),
                startOffset = calculateOffset(text, para),
                endOffset = calculateOffset(text, para) + para.length
            )
        }
    
    return DocumentStructure(
        documentType = "PLAINTEXT",
        sections = listOf(Section(
            id = UUID.randomUUID().toString(),
            level = 0,
            title = null,
            paragraphs = paragraphs
        ))
    )
}
```

#### For Markdown (Heading-based)
```kotlin
private fun parseMarkdownStructure(text: String): DocumentStructure {
    // Leverage MarkdownChunker's existing parsing logic
    val chunks = MarkdownChunker().chunk(text, "", "markdown")
    
    val sections = chunks
        .filter { it.kind == ChunkKind.MARKDOWN_SECTION }
        .map { chunk ->
            Section(
                id = UUID.randomUUID().toString(),
                level = detectHeadingLevel(chunk.summary),
                title = chunk.summary,
                paragraphs = listOf(Paragraph(
                    id = UUID.randomUUID().toString(),
                    ordinal = chunk.ordinal,
                    content = chunk.content,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine
                ))
            )
        }
    
    return DocumentStructure(
        documentType = "MARKDOWN",
        sections = sections
    )
}
```

---

## Integration Points

### Current Flow (DuckDB only)
```
File → Extractor → Text → Chunker → Chunks → DuckDB
                                              ↓
                                         Embeddings
```

### Target Flow (Neo4j + DuckDB)
```
File → Extractor → Text → Adapter → Structure → Neo4j
                    ↓                              ↓
                 Chunker → Chunks → DuckDB    (relationships)
                                      ↓
                                 Embeddings
```

### Coordination
- **Adapter** calls existing extractor, parses structure, stores in Neo4j
- **Chunker** continues to work as before, stores chunks in DuckDB
- **Indexer** links Neo4j nodes to DuckDB chunks via chunk IDs

---

## Benefits of Reuse

1. **Zero Risk**: Existing extractors are battle-tested
2. **Time Savings**: ~20 hours saved (no rewrite needed)
3. **Consistency**: Same text extraction for both systems
4. **Maintainability**: Single source of truth for extraction logic
5. **Incremental**: Can add Neo4j structure without breaking existing system

---

## Next Steps

### Task 2.2: Document Structure Adapter
- Create `DocumentStructureAdapter.kt`
- Implement `parseParagraphStructure()` for PDF/Word
- Implement `parseMarkdownStructure()` for Markdown
- Define `DocumentStructure`, `Section`, `Paragraph` data classes

### Task 2.3: Code Structure Extractor Interface
- Define interface for future AST extractors
- Create placeholder implementations for Kotlin/Java/Python/TS/JS
- Focus on document support first, code structure in later phases

---

## Acceptance Criteria

✅ **Task 2.1 Complete** when:
- [x] All existing extractors reviewed and documented
- [x] Reuse strategy defined and approved
- [x] Adapter architecture designed
- [x] Integration points identified
- [x] Next steps clearly outlined

**Status**: ✅ COMPLETE
