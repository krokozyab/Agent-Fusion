# Context Retrieval System - Detailed Implementation Plan

## Document Control
- **Project**: Universal Project Context Retrieval System
- **Version**: 1.0
- **Date**: October 12, 2025
- **Status**: Implementation Ready
- **Related**: CONTEXT_ADDON_ARCHITECTURE.md

---

## Implementation Overview

**Total Tasks**: 85 tasks across 9 phases
**Estimated Duration**: 16 weeks
**Team**: Codex CLI (architecture/planning), Claude Code (implementation)

**Task Naming Convention**: CONTEXT-XXX
**Priority Levels**: P0 (critical), P1 (high), P2 (medium), P3 (nice-to-have)
**Time Estimates**: Small (30min-2h), Medium (2h-1day), Large (1-3 days)

---

## Phase 0: Foundation & Database Schema (Week 1)

### CONTEXT-001: Database Schema - Core Tables
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Create DuckDB schema for core context tables

**Tasks**:
1. Create schema.sql file with:
   - file_state table (tracks indexed files)
   - chunks table (semantic text chunks)
   - embeddings table (vector representations)
   - links table (cross-file references)
   - usage_metrics table (token tracking)
2. Define all columns with proper types
3. Add PRIMARY KEY constraints
4. Add FOREIGN KEY constraints with CASCADE
5. Document each table and column

**Acceptance Criteria**:
- Schema file executes without errors in DuckDB
- All foreign key relationships correct
- Indexes created (defer to CONTEXT-002)

**Files to Create**:
- `src/main/resources/context/schema.sql`

---

### CONTEXT-002: Database Schema - Indexes
**Priority**: P0  
**Size**: Small (30min)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-001  

**Description**: Create database indexes for query performance

**Tasks**:
1. Add to schema.sql:
   - idx_chunks_file ON chunks(file_id, ordinal)
   - idx_chunks_kind ON chunks(kind)
   - idx_embeddings_model ON embeddings(model)
   - idx_file_state_path ON file_state(rel_path)
   - idx_file_state_mtime ON file_state(mtime_ns)

**Acceptance Criteria**:
- All indexes created successfully
- Query plan shows index usage for common queries

**Files to Update**:
- `src/main/resources/context/schema.sql`

---

### CONTEXT-003: Database Schema - Bootstrap Tables
**Priority**: P0  
**Size**: Small (30min)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-001  

**Description**: Create temporary tables for bootstrap process

**Tasks**:
1. Add to schema.sql:
   - bootstrap_progress table (tracks indexing progress)
   - bootstrap_errors table (logs failures)
   - jobs table (tracks rebuild operations)
   - project_config table (stores boundary configuration)
2. Add indexes for jobs table (status, created_at)

**Acceptance Criteria**:
- Bootstrap tables created
- Can track progress and errors
- Jobs table supports async operations

**Files to Update**:
- `src/main/resources/context/schema.sql`

---

### CONTEXT-004: Domain Models - Core Data Classes
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-001  

**Description**: Define Kotlin data classes for domain model

**Tasks**:
1. Create domain/context/ package
2. Define data classes:
   - FileState (file metadata and fingerprint)
   - Chunk (semantic text chunk)
   - Embedding (vector representation)
   - Link (cross-file reference)
   - ChunkKind enum
   - ContextScope
   - TokenBudget
   - ContextSnippet

**Acceptance Criteria**:
- All data classes have proper equals/hashCode
- Immutable where appropriate
- Well documented

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/domain/FileState.kt`
- `src/main/kotlin/com/orchestrator/context/domain/Chunk.kt`
- `src/main/kotlin/com/orchestrator/context/domain/Embedding.kt`
- `src/main/kotlin/com/orchestrator/context/domain/Link.kt`
- `src/main/kotlin/com/orchestrator/context/domain/ChunkKind.kt`
- `src/main/kotlin/com/orchestrator/context/domain/ContextScope.kt`
- `src/main/kotlin/com/orchestrator/context/domain/TokenBudget.kt`
- `src/main/kotlin/com/orchestrator/context/domain/ContextSnippet.kt`

---

### CONTEXT-005: Configuration Schema - Data Classes
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Define configuration data classes

**Tasks**:
1. Create config/context/ package
2. Define configuration classes:
   - ContextConfig (root config)
   - EngineConfig
   - StorageConfig
   - WatcherConfig
   - IndexingConfig
   - EmbeddingConfig
   - ChunkingConfig
   - QueryConfig
   - BudgetConfig
   - ProviderConfig
   - MetricsConfig
   - BootstrapConfig
   - SecurityConfig
3. Add DeploymentMode enum (EMBEDDED, STANDALONE, HYBRID)

**Acceptance Criteria**:
- All config classes defined
- Nested structure matches TOML schema
- Default values provided

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/config/ContextConfig.kt`

---

### CONTEXT-006: Configuration Loader
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-005  

**Description**: Implement TOML configuration loader

**Tasks**:
1. Create ContextConfigLoader object
2. Use Typesafe Config or TOML4j for parsing
3. Implement load(path: String): ContextConfig
4. Add validation:
   - Required fields present
   - watch_paths exist or "auto"
   - No dangerous paths (/, /etc, /sys)
   - Extension lists valid
   - Size limits reasonable
5. Add environment variable substitution
6. Add default configuration fallback

**Acceptance Criteria**:
- Loads valid context.toml successfully
- Validates and rejects invalid configs
- Clear error messages
- Environment variables work

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/config/ContextConfigLoader.kt`

**Files to Update**:
- `build.gradle.kts` (add TOML parsing dependency)

---

### CONTEXT-007: Database Connection Manager
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-001, CONTEXT-005  

**Description**: Create DuckDB connection manager

**Tasks**:
1. Create storage/ContextDatabase object
2. Implement:
   - initialize(config: StorageConfig)
   - getConnection(): Connection
   - executeSchema()
   - shutdown()
3. Handle schema creation/migration
4. Transaction support
5. Connection lifecycle management

**Acceptance Criteria**:
- Database initializes on first run
- Schema applied correctly
- Connection pooling if needed
- Clean shutdown

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/storage/ContextDatabase.kt`

---

### CONTEXT-008: Repository - FileStateRepository
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-004, CONTEXT-007  

**Description**: Implement file_state table repository

**Tasks**:
1. Create FileStateRepository object
2. Implement CRUD operations:
   - insert(fileState: FileState): UUID
   - findById(id: UUID): FileState?
   - findByPath(relPath: String): FileState?
   - findAll(limit: Int): List<FileState>
   - update(fileState: FileState)
   - delete(id: UUID)
   - deleteByPath(relPath: String)
3. Query methods:
   - findModifiedSince(timestamp: Instant): List<FileState>
   - findByLanguage(lang: String): List<FileState>
   - count(): Long
4. Raw JDBC, manual mapping (no ORM)

**Acceptance Criteria**:
- All CRUD operations work
- Queries return correct results
- Transactions used appropriately
- No SQL injection vulnerabilities

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/storage/FileStateRepository.kt`

---

### CONTEXT-009: Repository - ChunkRepository
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-004, CONTEXT-007  

**Description**: Implement chunks table repository

**Tasks**:
1. Create ChunkRepository object
2. Implement CRUD operations:
   - insert(chunk: Chunk): UUID
   - findById(id: UUID): Chunk?
   - findByFileId(fileId: UUID): List<Chunk>
   - deleteByFileId(fileId: UUID)
3. Query methods:
   - findByKind(kind: ChunkKind): List<Chunk>
   - findByLabel(label: String): List<Chunk>
   - count(): Long
4. Batch operations:
   - insertBatch(chunks: List<Chunk>): List<UUID>

**Acceptance Criteria**:
- CRUD operations work
- Cascading deletes via FK
- Batch inserts efficient

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/storage/ChunkRepository.kt`

---

### CONTEXT-010: Repository - EmbeddingRepository
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-004, CONTEXT-007  

**Description**: Implement embeddings table repository

**Tasks**:
1. Create EmbeddingRepository object
2. Implement CRUD operations:
   - insert(embedding: Embedding)
   - findByChunkId(chunkId: UUID, model: String): Embedding?
   - findByChunkIds(chunkIds: List<UUID>, model: String): List<Embedding>
   - deleteByChunkId(chunkId: UUID)
3. Vector operations:
   - Serialize float array to BLOB
   - Deserialize BLOB to float array
4. Batch operations:
   - insertBatch(embeddings: List<Embedding>)

**Acceptance Criteria**:
- Vector serialization works correctly
- Batch inserts efficient
- Cascading deletes work

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/storage/EmbeddingRepository.kt`

---

## Phase 1: Project Boundary & File Discovery (Week 2)

### CONTEXT-011: Project Root Auto-Detection
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-005  

**Description**: Implement auto-detection of project root

**Tasks**:
1. Create ProjectRootDetector object
2. Implement detection strategies:
   - findGitRoot(): Look for .git directory
   - findPackageRoot(): Look for package.json, pom.xml, Cargo.toml, etc.
   - findProjectStructure(): Look for src/ + tests/
   - getCurrentWorkingDir(): Fallback
3. Implement detect(): Path method
4. Return detected root with confidence score

**Acceptance Criteria**:
- Detects common project types correctly
- Falls back gracefully
- No false positives

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/ProjectRootDetector.kt`

---

### CONTEXT-012: Path Filter - Ignore Patterns
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-005  

**Description**: Implement pattern-based path filtering

**Tasks**:
1. Create PathFilter class
2. Support glob patterns (*, **, ?)
3. Load patterns from:
   - Configuration (ignore_patterns)
   - .gitignore file
   - .contextignore file
   - .dockerignore file
4. Implement shouldIgnore(path: Path): Boolean
5. Case-insensitive option

**Acceptance Criteria**:
- Glob patterns work correctly
- Multiple ignore sources combined
- Performance efficient (compiled patterns)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/PathFilter.kt`

---

### CONTEXT-013: Extension Filter
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-005  

**Description**: Filter files by extension allowlist/blocklist

**Tasks**:
1. Create ExtensionFilter class
2. Support allowlist mode (only these extensions)
3. Support blocklist mode (exclude these extensions)
4. Implement shouldInclude(path: Path): Boolean
5. Case-insensitive extension matching

**Acceptance Criteria**:
- Allowlist mode works
- Blocklist mode works
- Both modes mutually exclusive

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/ExtensionFilter.kt`

---

### CONTEXT-014: Binary File Detector
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Detect binary files to skip indexing

**Tasks**:
1. Create BinaryDetector object
2. Implement detection methods:
   - detectByExtension(path: Path): Boolean
   - detectByMimeType(path: Path): Boolean
   - detectByContent(path: Path): Boolean
3. Content detection:
   - Read first 8KB
   - Check for null bytes
   - Calculate non-ASCII ratio
   - Threshold: >30% = binary
4. Combine all methods

**Acceptance Criteria**:
- Correctly identifies common binaries (.exe, .jpg, .zip)
- Correctly identifies text files
- Efficient (early termination)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/BinaryDetector.kt`

---

### CONTEXT-015: Symlink Handler
**Priority**: P1  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-005  

**Description**: Handle symbolic links with security checks

**Tasks**:
1. Create SymlinkHandler class
2. Implement:
   - shouldFollow(link: Path, config: IndexingConfig): Boolean
   - resolveTarget(link: Path): Path?
   - isEscape(link: Path, allowedRoots: List<Path>): Boolean
3. Track visited inodes to prevent loops
4. Enforce max_symlink_depth
5. Reject escapes outside watch_paths

**Acceptance Criteria**:
- Follows symlinks when configured
- Detects and prevents loops
- Blocks escapes to /tmp, /home, etc.

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/SymlinkHandler.kt`

---

### CONTEXT-016: Path Validator
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-012, CONTEXT-013, CONTEXT-014, CONTEXT-015  

**Description**: Central path validation and security

**Tasks**:
1. Create PathValidator class
2. Implement validation pipeline:
   - isUnderWatchPaths(path: Path, watchPaths: List<Path>): Boolean
   - containsPathTraversal(path: Path): Boolean
   - isInIgnorePatterns(path: Path): Boolean
   - isAllowedExtension(path: Path): Boolean
   - isBinary(path: Path): Boolean
   - isSymlinkEscape(path: Path): Boolean
   - isWithinSizeLimit(path: Path): Boolean
3. Implement validate(path: Path): ValidationResult
4. Return detailed rejection reasons

**Acceptance Criteria**:
- All security checks pass
- Rejects dangerous paths
- Clear error messages
- Efficient (short-circuit evaluation)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/PathValidator.kt`

---

### CONTEXT-017: Directory Scanner
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-016  

**Description**: Recursively scan directories for indexable files

**Tasks**:
1. Create DirectoryScanner class
2. Implement:
   - scan(roots: List<Path>, validator: PathValidator): List<Path>
   - Recursive traversal
   - Apply PathValidator to each file
   - Skip ignored directories early
   - Progress reporting (optional)
3. Return list of indexable file paths
4. Parallel scanning option (multiple roots)

**Acceptance Criteria**:
- Finds all indexable files
- Respects ignore patterns
- Efficient (prunes branches early)
- Handles large directory trees

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/discovery/DirectoryScanner.kt`

---

### CONTEXT-018: File Prioritizer
**Priority**: P1  
**Size**: Small (1h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-005  

**Description**: Prioritize files for bootstrap indexing

**Tasks**:
1. Create FilePrioritizer object
2. Implement prioritization logic:
   - Priority 1: Small files (<10KB)
   - Priority 2: Source code (.kt, .py, .ts, .java)
   - Priority 3: Documentation (.md, .rst)
   - Priority 4: Configuration (.yaml, .toml, .json)
   - Priority 5: Large files (up to limit)
3. Implement prioritize(files: List<Path>): List<Path>
4. Stable sort (preserve relative order within priority)

**Acceptance Criteria**:
- Files sorted by priority correctly
- Configuration-driven (priority_extensions)
- Efficient sorting

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/bootstrap/FilePrioritizer.kt`

---

## Phase 2: Chunking Strategies (Week 3-4)

### CONTEXT-019: Chunker Interface
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-004  

**Description**: Define chunking strategy interface

**Tasks**:
1. Create Chunker interface
2. Define methods:
   - chunk(content: String, filePath: String, language: String): List<Chunk>
   - estimateTokens(text: String): Int
3. Define ChunkingStrategy for configuration
4. Document interface contract

**Acceptance Criteria**:
- Interface well-defined
- All chunkers will implement this
- Token estimation method included

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/Chunker.kt`

---

### CONTEXT-020: Token Estimator
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Estimate token count for text

**Tasks**:
1. Create TokenEstimator object
2. Implement simple heuristic:
   - 4 characters ≈ 1 token
   - Count words for better estimate
3. Optional: Use tiktoken library for accuracy
4. Cache results for performance

**Acceptance Criteria**:
- Estimates within 20% of actual
- Fast (<1ms for typical chunk)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/TokenEstimator.kt`

---

### CONTEXT-021: Markdown Chunker
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019, CONTEXT-020  

**Description**: Chunk Markdown files by headings

**Tasks**:
1. Create MarkdownChunker class
2. Parse Markdown to find ATX headings (#, ##, ###)
3. Split by headings, each section = chunk
4. Extract heading text as label
5. Preserve code fences as standalone chunks
6. Further split long sections by paragraphs
7. Respect max_tokens (400)
8. Set chunk.kind = MARKDOWN_SECTION or CODE_BLOCK

**Acceptance Criteria**:
- Splits by headings correctly
- Code blocks preserved
- Labels extracted
- Token limits respected

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/MarkdownChunker.kt`

---

### CONTEXT-022: Python Chunker
**Priority**: P0  
**Size**: Large (6h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019, CONTEXT-020  

**Description**: Chunk Python files by functions/classes

**Tasks**:
1. Create PythonChunker class
2. Parse Python AST (use Jython or external tool)
3. Extract module docstring as first chunk
4. Extract each class as chunk (with docstring)
5. Extract each function as chunk (with docstring)
6. For long functions, split by statement blocks
7. Respect max_tokens (600) with 15% overlap
8. Set chunk.kind appropriately (CODE_CLASS, CODE_FUNCTION, DOCSTRING)

**Acceptance Criteria**:
- Parses valid Python correctly
- Extracts functions and classes
- Preserves docstrings
- Handles edge cases (nested classes, decorators)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/PythonChunker.kt`

---

### CONTEXT-023: TypeScript/JavaScript Chunker
**Priority**: P0  
**Size**: Large (6h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019, CONTEXT-020  

**Description**: Chunk TypeScript/JavaScript by exports

**Tasks**:
1. Create TypeScriptChunker class
2. Parse TypeScript AST (use tree-sitter or Babel via Node)
3. Extract exported declarations
4. Keep JSDoc with function/class
5. Include relevant imports
6. Respect max_tokens (600)
7. Set chunk.kind appropriately

**Acceptance Criteria**:
- Parses TypeScript and JavaScript
- Extracts exports correctly
- Preserves JSDoc
- Handles ES6 syntax

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/TypeScriptChunker.kt`

---

### CONTEXT-024: Kotlin Chunker
**Priority**: P0  
**Size**: Large (6h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019, CONTEXT-020  

**Description**: Chunk Kotlin files by classes/functions

**Tasks**:
1. Create KotlinChunker class
2. Parse Kotlin AST (use kotlinc or kotlin-compiler-embeddable)
3. Extract package-level docstring
4. Extract each class/interface as chunk
5. Extract each function as chunk
6. Preserve KDoc comments
7. Respect max_tokens (600)
8. Set chunk.kind appropriately

**Acceptance Criteria**:
- Parses Kotlin correctly
- Extracts classes and functions
- Preserves KDoc
- Handles Kotlin-specific syntax (data classes, sealed, etc.)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/KotlinChunker.kt`

---

### CONTEXT-025: YAML Chunker
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019, CONTEXT-020  

**Description**: Chunk YAML by top-level keys

**Tasks**:
1. Create YamlChunker class
2. Parse YAML (use SnakeYAML)
3. Split by top-level keys
4. Each key/value = chunk
5. For large multiline strings, split further
6. Label with key path (e.g., "services.database")
7. Set chunk.kind = YAML_BLOCK

**Acceptance Criteria**:
- Parses valid YAML
- Splits by keys correctly
- Handles nested structures
- Preserves key paths

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/YamlChunker.kt`

---

### CONTEXT-026: SQL Chunker
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019, CONTEXT-020  

**Description**: Chunk SQL by statements

**Tasks**:
1. Create SqlChunker class
2. Simple statement splitter (split on semicolons)
3. Attach preceding comments to statement
4. Each statement = chunk
5. Label by statement type + table name (e.g., "CREATE users")
6. Set chunk.kind = SQL_STATEMENT

**Acceptance Criteria**:
- Splits SQL statements correctly
- Preserves comments
- Handles multi-line statements
- Extracts labels

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/SqlChunker.kt`

---

### CONTEXT-027: Chunker Registry
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-021 through CONTEXT-026  

**Description**: Registry for mapping file types to chunkers

**Tasks**:
1. Create ChunkerRegistry object
2. Map file extensions to chunkers:
   - .md → MarkdownChunker
   - .py → PythonChunker
   - .ts, .js, .tsx, .jsx → TypeScriptChunker
   - .kt → KotlinChunker
   - .yaml, .yml → YamlChunker
   - .sql → SqlChunker
3. Implement getChunker(filePath: Path): Chunker?
4. Fallback to plain text chunker for unknown types

**Acceptance Criteria**:
- Correct chunker selected for each type
- Fallback works for unknown types

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/ChunkerRegistry.kt`

---

### CONTEXT-028: Plain Text Chunker (Fallback)
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-019  

**Description**: Generic chunker for unknown file types

**Tasks**:
1. Create PlainTextChunker class
2. Split by paragraphs (double newline)
3. Respect max_tokens
4. No labels
5. Set chunk.kind = PARAGRAPH

**Acceptance Criteria**:
- Handles any text file
- Reasonable chunk boundaries

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/chunking/PlainTextChunker.kt`

---

## Phase 3: Embedding & Vector Operations (Week 5)

### CONTEXT-029: Embedder Interface
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-004  

**Description**: Define embedding service interface

**Tasks**:
1. Create Embedder interface
2. Define methods:
   - embed(text: String): FloatArray
   - embedBatch(texts: List<String>): List<FloatArray>
   - getDimension(): Int
   - getModel(): String
3. Support async/coroutines

**Acceptance Criteria**:
- Interface supports batch operations
- Dimension known at compile time
- Model name accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/embedding/Embedder.kt`

---

### CONTEXT-030: Local Sentence Transformer Embedder
**Priority**: P0  
**Size**: Large (1 day)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-029  

**Description**: Implement local embedding using sentence-transformers

**Tasks**:
1. Create LocalEmbedder class
2. Use sentence-transformers via Python subprocess or ONNX runtime
3. Load configured model (e.g., all-MiniLM-L6-v2)
4. Implement embed() and embedBatch()
5. Normalize vectors if configured
6. Cache model in memory
7. Handle batching efficiently

**Acceptance Criteria**:
- Generates embeddings correctly
- Batch processing efficient
- Normalization works
- Model loads on first use

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/embedding/LocalEmbedder.kt`

**Dependencies to Add**:
- ONNX Runtime or Python integration library

---

### CONTEXT-031: Vector Operations Utility
**Priority**: P0  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Utility functions for vector math

**Tasks**:
1. Create VectorOps object
2. Implement:
   - normalize(vec: FloatArray): FloatArray (L2 normalization)
   - dotProduct(a: FloatArray, b: FloatArray): Float
   - cosineSimilarity(a: FloatArray, b: FloatArray): Float
   - serialize(vec: FloatArray): ByteArray
   - deserialize(bytes: ByteArray): FloatArray
3. Optimize for performance

**Acceptance Criteria**:
- Math operations correct
- Serialization round-trips
- Performance adequate (vectorized if possible)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/embedding/VectorOps.kt`

---

### CONTEXT-032: Vector Search Engine
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-010, CONTEXT-031  

**Description**: Implement cosine similarity search

**Tasks**:
1. Create VectorSearchEngine class
2. Implement:
   - search(queryVector: FloatArray, k: Int, filters: Filters): List<ScoredChunk>
   - Load embeddings from EmbeddingRepository
   - Compute cosine similarity with all embeddings
   - Apply filters (language, kind, paths)
   - Return top-K by score
3. Optimize: pre-normalize vectors in DB

**Acceptance Criteria**:
- Returns correct top-K results
- Scores accurate (cosine similarity)
- Filters applied correctly
- Performance <250ms for 10k chunks

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt`

---

### CONTEXT-033: MMR Re-ranker
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-031, CONTEXT-032  

**Description**: Implement Maximal Marginal Relevance re-ranking

**Tasks**:
1. Create MmrReranker class
2. Implement rerank(results: List<ScoredChunk>, lambda: Double, budget: TokenBudget): List<ScoredChunk>
3. Greedy MMR algorithm:
   - Select highest score first
   - Iteratively add items maximizing: λ*relevance - (1-λ)*maxSimilarity
   - Stop when budget exhausted
4. Use VectorOps for similarity

**Acceptance Criteria**:
- Produces diverse results
- Respects token budget
- Lambda parameter works (0.0 to 1.0)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/search/MmrReranker.kt`

---

## Phase 4: Indexing Pipeline (Week 6)

### CONTEXT-034: File Hasher
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Compute fast file fingerprints

**Tasks**:
1. Create FileHasher object
2. Use BLAKE3 for fast hashing
3. Implement computeHash(path: Path): ByteArray
4. Stream large files (don't load all in memory)

**Acceptance Criteria**:
- Fast hashing (>100 MB/sec)
- Correct BLAKE3 implementation
- Handles large files

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/indexing/FileHasher.kt`

**Dependencies to Add**:
- BLAKE3 library (if available) or fallback to SHA-256

---

### CONTEXT-035: File Metadata Extractor
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-034  

**Description**: Extract file metadata for change detection

**Tasks**:
1. Create FileMetadataExtractor object
2. Implement extractMetadata(path: Path): FileMetadata
3. Extract:
   - Size in bytes
   - mtime in nanoseconds
   - Content hash (via FileHasher)
   - Language/MIME type detection
4. Return FileMetadata data class

**Acceptance Criteria**:
- All metadata extracted correctly
- Language detection works for common types
- Efficient (single file read for hash)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/indexing/FileMetadataExtractor.kt`

---

### CONTEXT-036: Indexer - Single File
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-027, CONTEXT-030, CONTEXT-035  

**Description**: Index a single file (chunk + embed + store)

**Tasks**:
1. Create FileIndexer class
2. Implement indexFile(path: Path): IndexResult
3. Process flow:
   - Extract metadata
   - Read file content
   - Select chunker based on extension
   - Chunk file
   - Estimate tokens for each chunk
   - Batch chunks for embedding
   - Generate embeddings
   - Begin transaction
   - Upsert file_state
   - Delete old chunks/embeddings by file_id
   - Insert new chunks
   - Insert new embeddings
   - Commit transaction
4. Return IndexResult (success, chunk count, errors)

**Acceptance Criteria**:
- Successfully indexes files
- Transaction atomicity
- Handles errors gracefully
- Returns detailed result

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/indexing/FileIndexer.kt`

---

### CONTEXT-037: Batch Indexer
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-036  

**Description**: Index multiple files in parallel

**Tasks**:
1. Create BatchIndexer class
2. Implement indexFiles(paths: List<Path>, parallelism: Int): BatchResult
3. Use coroutine worker pool
4. Configurable concurrency (default: CPU cores - 1)
5. Progress tracking (files processed, errors)
6. Error handling (continue on individual failures)
7. Return BatchResult (successes, failures, stats)

**Acceptance Criteria**:
- Parallel processing works
- Progress tracking accurate
- Individual failures don't stop batch
- Final result complete

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/indexing/BatchIndexer.kt`

---

### CONTEXT-038: Change Detector
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-008, CONTEXT-035  

**Description**: Detect which files need reindexing

**Tasks**:
1. Create ChangeDetector class
2. Implement detectChanges(paths: List<Path>): ChangeSet
3. For each path:
   - Extract current metadata
   - Look up file_state by path
   - Compare hash, size, mtime
   - Classify: NEW, MODIFIED, UNCHANGED, DELETED
4. Return ChangeSet (new, modified, deleted lists)

**Acceptance Criteria**:
- Correctly detects new files
- Correctly detects modifications
- Correctly detects deletions
- Efficient (batch DB queries)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/indexing/ChangeDetector.kt`

---

### CONTEXT-039: Incremental Indexer
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-037, CONTEXT-038  

**Description**: Orchestrate incremental updates

**Tasks**:
1. Create IncrementalIndexer class
2. Implement update(paths: List<Path>): UpdateResult
3. Process flow:
   - Detect changes
   - Index new/modified files via BatchIndexer
   - Delete file_state for removed files (cascades)
   - Track metrics
4. Return UpdateResult (new, modified, deleted counts)

**Acceptance Criteria**:
- Only changed files processed
- Deletions cascade correctly
- Efficient for large file sets

**Implementation Notes**:
- DuckDB evaluates foreign-key constraints at each parent DELETE within the same transaction, even if child rows were already removed earlier in that transaction. To satisfy this, `ContextRepository.deleteFileArtifacts` now runs in three staged transactions—child artifacts first, then `chunks`, and finally `file_state`. This guarantees each stage commits before the next FK check.
- The deletion routine is idempotent. If a file has already been removed the method returns success without throwing.
- A `restoreArtifacts` helper replays the previously persisted state if any stage fails midway, preventing partially deleted records from being left behind.

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/indexing/IncrementalIndexer.kt`

---

## Phase 5: File Watching (Week 7)

### CONTEXT-040: File Watcher - Event Listener
**Priority**: P1  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-005  

**Description**: Watch file system for changes

**Tasks**:
1. Create FileWatcher class
2. Use java.nio.file.WatchService
3. Watch configured directories (watch_paths)
4. Detect events: CREATE, MODIFY, DELETE
5. Emit events to channel/flow
6. Handle overflow events
7. Support recursive watching

**Acceptance Criteria**:
- Detects file changes within 2 seconds
- Recursive watching works
- No dropped events under normal load

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/watcher/FileWatcher.kt`

---

### CONTEXT-041: Event Debouncer
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-040  

**Description**: Debounce rapid file change events

**Tasks**:
1. Create EventDebouncer class
2. Collect events for debounce_ms (default 500ms)
3. Merge duplicate events (same path)
4. Emit debounced events
5. Use coroutines for timing

**Acceptance Criteria**:
- Multiple rapid changes → single event
- Configurable debounce delay
- No event loss

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/watcher/EventDebouncer.kt`

---

### CONTEXT-042: Watcher Daemon
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-039, CONTEXT-041  

**Description**: Background service for watching and updating

**Tasks**:
1. Create WatcherDaemon class
2. Start FileWatcher on configured paths
3. Debounce events
4. Filter events through PathValidator
5. Batch events (collect for 1 second)
6. Trigger IncrementalIndexer
7. Handle errors gracefully
8. Lifecycle: start(), stop()

**Acceptance Criteria**:
- Auto-updates index on file changes
- Batching reduces overhead
- Errors logged but don't stop daemon
- Clean shutdown

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/watcher/WatcherDaemon.kt`

---

## Phase 6: Bootstrap Process (Week 8)

### CONTEXT-043: Bootstrap Progress Tracker
**Priority**: P0  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-003, CONTEXT-007  

**Description**: Track bootstrap indexing progress

**Tasks**:
1. Create BootstrapProgressTracker class
2. Use bootstrap_progress table
3. Implement:
   - initProgress(files: List<Path>)
   - markProcessing(path: Path)
   - markCompleted(path: Path)
   - markFailed(path: Path, error: String)
   - getProgress(): ProgressStats
   - getRemaining(): List<Path>
4. Support resumption after interruption

**Acceptance Criteria**:
- Progress persisted to database
- Can resume from any point
- Accurate statistics

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/bootstrap/BootstrapProgressTracker.kt`

---

### CONTEXT-044: Bootstrap Error Logger
**Priority**: P1  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-003, CONTEXT-007  

**Description**: Log files that fail during bootstrap

**Tasks**:
1. Create BootstrapErrorLogger class
2. Use bootstrap_errors table
3. Implement:
   - logError(path: Path, error: Throwable)
   - getErrors(): List<ErrorRecord>
   - clearErrors()
   - retryFailed(): List<Path>

**Acceptance Criteria**:
- Errors persisted with stack traces
- Can retry failed files
- Clear errors after resolution

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/bootstrap/BootstrapErrorLogger.kt`

---

### CONTEXT-045: Bootstrap Orchestrator
**Priority**: P0  
**Size**: Large (1 day)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-017, CONTEXT-018, CONTEXT-037, CONTEXT-043, CONTEXT-044  

**Description**: Orchestrate complete bootstrap process

**Tasks**:
1. Create BootstrapOrchestrator class
2. Implement bootstrap(): BootstrapResult
3. Process flow:
   - Check if resuming (bootstrap_progress exists)
   - If new: scan directories, create progress
   - If resuming: load remaining files
   - Prioritize files
   - Index in batches (BatchIndexer)
   - Track progress
   - Log errors
   - Clean up progress table on completion
4. Progress notifications (callback or flow)
5. Incremental availability (queryable before complete)

**Acceptance Criteria**:
- Full bootstrap completes successfully
- Resumption works after interruption
- Progress tracking accurate
- Errors handled gracefully

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/bootstrap/BootstrapOrchestrator.kt`

---

### CONTEXT-046: Project Config Validator
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-003, CONTEXT-006, CONTEXT-016  

**Description**: Validate and track project configuration

**Tasks**:
1. Create ProjectConfigValidator class
2. Use project_config table
3. Implement:
   - saveConfig(config: ContextConfig)
   - loadConfig(): ContextConfig?
   - detectChanges(newConfig: ContextConfig): ConfigChanges
   - validate(config: ContextConfig): ValidationResult
4. Validation checks:
   - watch_paths exist
   - No dangerous paths
   - Extension lists valid
   - Security boundaries safe

**Acceptance Criteria**:
- Config persisted to database
- Changes detected correctly
- Validation comprehensive
- Clear error messages

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/bootstrap/ProjectConfigValidator.kt`

---

## Phase 7: Provider Implementations (Week 9-10)

### CONTEXT-047: Provider Interface
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-004  

**Description**: Define ContextProvider interface

**Tasks**:
1. Create ContextProvider interface
2. Define methods per architecture spec
3. Add ContextProviderType enum
4. Document contract

**Acceptance Criteria**:
- Interface matches architecture
- Well documented
- Ready for implementations

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/ContextProvider.kt`

---

### CONTEXT-048: Semantic Context Provider
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-032, CONTEXT-033, CONTEXT-047  

**Description**: Implement vector-based semantic search

**Tasks**:
1. Create SemanticContextProvider class
2. Implement ContextProvider interface
3. Process flow:
   - Embed query using Embedder
   - Search using VectorSearchEngine
   - Apply filters from ContextScope
   - Re-rank using MmrReranker
   - Respect TokenBudget
   - Return ContextSnippets
4. Configuration: weight, enabled

**Acceptance Criteria**:
- Semantic search works
- Returns relevant results
- Respects budget
- Handles errors

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/SemanticContextProvider.kt`

---

### CONTEXT-049: Symbol Index Builder
**Priority**: P1  
**Size**: Large (1 day)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-027  

**Description**: Build AST-based symbol index

**Tasks**:
1. Create SymbolIndexBuilder class
2. Parse source files using tree-sitter
3. Extract symbols:
   - Classes
   - Functions/methods
   - Variables
   - Imports
4. Store in symbols table (new table in schema)
5. Support: Kotlin, Python, TypeScript, Java

**Acceptance Criteria**:
- Symbols extracted correctly
- Index queryable
- Performance acceptable

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/SymbolIndexBuilder.kt`
- Update schema.sql with symbols table

---

### CONTEXT-050: Symbol Context Provider
**Priority**: P1  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-047, CONTEXT-049  

**Description**: Implement symbol-based search

**Tasks**:
1. Create SymbolContextProvider class
2. Implement ContextProvider interface
3. Process flow:
   - Extract symbols from query (heuristic)
   - Lookup in symbol index
   - Find definitions + call sites
   - Map to chunks
   - Return ContextSnippets
4. Configuration: weight, index_ast

**Acceptance Criteria**:
- Symbol lookup works
- Fast response (<50ms)
- Finds definitions and usages

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/SymbolContextProvider.kt`

---

### CONTEXT-051: Full-Text Context Provider
**Priority**: P2  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-047  

**Description**: Implement BM25 keyword search

**Tasks**:
1. Create FullTextContextProvider class
2. Use DuckDB full-text search (FTS)
3. Implement BM25 scoring
4. Query chunks.text with keywords
5. Return ranked results

**Acceptance Criteria**:
- Keyword search works
- BM25 scoring correct
- Fast (<100ms)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/FullTextContextProvider.kt`

---

### CONTEXT-052: Git History Analyzer
**Priority**: P2  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: None  

**Description**: Analyze git commit history

**Tasks**:
1. Create GitHistoryAnalyzer class
2. Use JGit library
3. Implement:
   - getRecentCommits(path: Path, limit: Int): List<Commit>
   - getBlame(path: Path): Map<Line, Author>
   - findCoChangedFiles(path: Path): List<Path>
4. Cache results

**Acceptance Criteria**:
- Git operations work
- Recent commits retrieved
- Co-change detection works

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/GitHistoryAnalyzer.kt`

**Dependencies to Add**:
- JGit library

---

### CONTEXT-053: Git History Context Provider
**Priority**: P2  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-047, CONTEXT-052  

**Description**: Provide git-based context

**Tasks**:
1. Create GitHistoryContextProvider class
2. Implement ContextProvider interface
3. Process flow:
   - Extract paths from query
   - Get recent commits via GitHistoryAnalyzer
   - Find co-changed files
   - Map to chunks
   - Return ContextSnippets with commit metadata

**Acceptance Criteria**:
- Provides temporal context
- Shows recent changes
- Co-change detection helpful

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/GitHistoryContextProvider.kt`

---

### CONTEXT-054: Hybrid Context Provider
**Priority**: P1  
**Size**: Medium (4h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-048, CONTEXT-050, CONTEXT-053  

**Description**: Combine multiple providers with RRF

**Tasks**:
1. Create HybridContextProvider class
2. Implement ContextProvider interface
3. Configuration: combines list, fusion_strategy
4. Process flow:
   - Query all configured sub-providers in parallel
   - Collect results with scores
   - Apply Reciprocal Rank Fusion:
     - RRF(chunk) = Σ 1/(k + rank_in_provider_i)
     - k = 60
   - Re-rank by fused scores
   - Return top-K
5. Handle provider failures gracefully

**Acceptance Criteria**:
- Queries multiple providers
- RRF merging works correctly
- Results better than individual providers
- Resilient to provider failures

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/HybridContextProvider.kt`

---

### CONTEXT-055: Provider Registry
**Priority**: P0  
**Size**: Small (1h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-047 through CONTEXT-054  

**Description**: SPI-based provider discovery

**Tasks**:
1. Create ContextProviderRegistry object
2. Use Java ServiceLoader
3. Discover all ContextProvider implementations
4. Register in map by ID
5. Implement:
   - getProvider(id: String): ContextProvider?
   - getAllProviders(): List<ContextProvider>
   - getProvidersByType(type: ContextProviderType): List<ContextProvider>

**Acceptance Criteria**:
- SPI discovery works
- All providers registered
- Zero hardcoding

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/providers/ContextProviderRegistry.kt`
- `src/main/resources/META-INF/services/com.orchestrator.context.providers.ContextProvider`

---

## Phase 8: Orchestrator Integration (Week 11-12)

### CONTEXT-056: Context Module - Query Optimizer
**Priority**: P0  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-033  

**Description**: Wrap MMR re-ranker for module use

**Tasks**:
1. Create QueryOptimizer class
2. Configuration: QueryConfig
3. Wrapper around MmrReranker
4. Add caching for repeated queries

**Acceptance Criteria**:
- Re-ranks results correctly
- Caching improves performance

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/QueryOptimizer.kt`

---

### CONTEXT-057: Context Module - Budget Manager
**Priority**: P0  
**Size**: Medium (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-004, CONTEXT-020  

**Description**: Calculate token budgets per architecture

**Tasks**:
1. Create BudgetManager class
2. Implement calculateBudget(task: Task, agent: AgentId): TokenBudget
3. Adjust based on:
   - Task complexity
   - Task type
   - Agent capabilities
4. Warn on high budgets

**Acceptance Criteria**:
- Budget calculation matches architecture spec
- Warnings logged appropriately

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/BudgetManager.kt`

---

### CONTEXT-058: Context Module - Main Integration
**Priority**: P0  
**Size**: Large (1 day)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-055, CONTEXT-056, CONTEXT-057  

**Description**: Main context module for orchestrator

**Tasks**:
1. Create ContextModule class
2. Initialize providers from ContextProviderRegistry
3. Implement getTaskContext(task: Task, agent: AgentId): TaskContext
4. Process flow per architecture:
   - Build query from task
   - Determine scope
   - Calculate budget
   - Query providers in parallel
   - Merge and deduplicate results
   - Apply MMR re-ranking
   - Record metrics
5. Error handling with fallback
6. Implement shutdown()

**Acceptance Criteria**:
- Integration works end-to-end
- Fallback mode works
- Metrics recorded
- Performance targets met

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/ContextModule.kt`

---

### CONTEXT-059: Task Context Renderer
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-058  

**Description**: Render TaskContext as XML for prompts

**Tasks**:
1. Create TaskContextRenderer object
2. Implement render(context: TaskContext): String
3. Generate XML format:
   ```
   <project_context>
     <snippet path="..." label="..." kind="..." score="...">
     ...
     </snippet>
   </project_context>
   ```
4. Sort by score
5. Group by file for readability
6. Truncate if needed for budget
7. Add language hints

**Acceptance Criteria**:
- XML well-formed
- Readable format
- Respects budget
- Properly escaped

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/TaskContextRenderer.kt`

---

### CONTEXT-060: Orchestrator Hook Integration
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-058, CONTEXT-059  

**Description**: Integrate context into task workflow

**Tasks**:
1. Modify RoutingModule (or create hook)
2. Call ContextModule.getTaskContext() before task assignment
3. Render context to XML
4. Inject into agent prompt
5. Update prompt templates
6. Add context-enabled flag to task metadata

**Acceptance Criteria**:
- Context retrieved automatically
- Injected into prompts
- No breaking changes to existing flow
- Can be disabled via config

**Files to Update**:
- `src/main/kotlin/com/orchestrator/modules/routing/RoutingModule.kt` (or similar)
- Prompt templates

---

### CONTEXT-061: Metrics Collector
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-007, CONTEXT-058  

**Description**: Collect and store context metrics

**Tasks**:
1. Create ContextMetricsCollector class
2. Use usage_metrics table
3. Record per-task:
   - tokens_input (with context)
   - tokens_output
   - context_tokens
   - query_latency_ms
   - snippets_count
   - providers_used
4. Implement:
   - record(task: Task, context: TaskContext, timing: Duration)
   - getStats(): MetricsStats
   - compareBeforeAfter(): ComparisonReport

**Acceptance Criteria**:
- Metrics persisted correctly
- Comparison reports accurate
- Performance negligible overhead

**Files to Create**:
- `src/main/kotlin/com/orchestrator/modules/context/ContextMetricsCollector.kt`

---

## Phase 9: MCP Tools (Week 13)

### CONTEXT-062: MCP Tool - query_context
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-058  

**Description**: Explicit context query tool for agents

**Tasks**:
1. Create QueryContextTool class
2. Implement execute(params: Params): Result
3. Parameters: query, k, scope, filters
4. Process flow:
   - Build ContextScope from params
   - Create TokenBudget from params
   - Call ContextModule (or providers directly)
   - Convert results to DTO format
   - Return hits with metadata

**Acceptance Criteria**:
- Tool callable via MCP
- Returns relevant results
- Filters work correctly

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/QueryContextTool.kt`

---

### CONTEXT-063: MCP Tool - refresh_context
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-039, CONTEXT-042  

**Description**: Manual re-indexing tool

**Tasks**:
1. Create RefreshContextTool class
2. Implement execute(params: Params): Result
3. Parameters: paths, force, async
4. Process flow:
   - Validate paths
   - If async: queue and return jobId
   - If sync: trigger IncrementalIndexer and wait
   - Return status and counts

**Acceptance Criteria**:
- Manual refresh works
- Async mode works
- Sync mode blocks correctly

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/RefreshContextTool.kt`

---

### CONTEXT-064: MCP Tool - get_context_stats
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-055, CONTEXT-061  

**Description**: Return statistics about context system

**Tasks**:
1. Create GetContextStatsTool class
2. Implement execute(params: Params): Result
3. Gather statistics:
   - Provider status
   - Storage stats (files, chunks, embeddings, size)
   - Performance metrics
   - Language distribution
   - Recent activity
4. Return comprehensive stats object

**Acceptance Criteria**:
- All stats accurate
- Performance acceptable
- Useful for debugging

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/GetContextStatsTool.kt`

---

### CONTEXT-065: MCP Tool - rebuild_context
**Priority**: P1  
**Size**: Large (6h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-045  

**Description**: Full rebuild tool with safety checks

**Tasks**:
1. Create RebuildContextTool class
2. Implement execute(params: Params): Result
3. Parameters: confirm, async, paths, options
4. Safety checks:
   - confirm = true required
   - Block if another rebuild in progress
   - Validate paths
5. Process flow (per architecture):
   - Validation phase
   - Pre-rebuild (create job record)
   - Destructive phase (drop tables/delete records)
   - Rebuild phase (run BootstrapOrchestrator)
   - Post-rebuild (vacuum, stats)
   - Return jobId or final status
6. Support validateOnly (dry-run)

**Acceptance Criteria**:
- Safety checks prevent accidents
- Full rebuild works
- Partial rebuild works
- Dry-run mode works
- Async execution works

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/RebuildContextTool.kt`

---

### CONTEXT-066: MCP Tool - get_rebuild_status
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-065  

**Description**: Check rebuild job status

**Tasks**:
1. Create GetRebuildStatusTool class
2. Implement execute(params: Params): Result
3. Parameters: jobId, includeLogs
4. Query jobs table for status
5. Return progress details

**Acceptance Criteria**:
- Returns current status
- Progress accurate
- Logs included when requested

**Files to Create**:
- `src/main/kotlin/com/orchestrator/mcp/tools/GetRebuildStatusTool.kt`

---

### CONTEXT-067: MCP Server Integration
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-062 through CONTEXT-066  

**Description**: Register context tools in MCP server

**Tasks**:
1. Update McpServerImpl
2. Register all context tools
3. Add routes for context tools
4. Update tools list endpoint
5. Test all tools via HTTP

**Acceptance Criteria**:
- All tools callable
- Proper error handling
- Tools show in list

**Files to Update**:
- `src/main/kotlin/com/orchestrator/mcp/McpServerImpl.kt`

---

## Phase 10: CLI & Validation (Week 14)

### CONTEXT-068: CLI - validate-config
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-046  

**Description**: CLI command to validate configuration

**Tasks**:
1. Add validate-config subcommand
2. Load context.toml
3. Run ProjectConfigValidator
4. Print validation results
5. Exit with appropriate code

**Acceptance Criteria**:
- Validates configuration
- Clear output
- Proper exit codes

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/cli/ValidateConfigCommand.kt`

---

### CONTEXT-069: CLI - list-indexable
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-017  

**Description**: Show files that would be indexed

**Tasks**:
1. Add list-indexable subcommand
2. Scan directories
3. Apply all filters
4. Print file list (with --limit option)
5. Show statistics

**Acceptance Criteria**:
- Lists correct files
- Statistics accurate
- Performance acceptable

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/cli/ListIndexableCommand.kt`

---

### CONTEXT-070: CLI - check-file
**Priority**: P1  
**Size**: Small (1h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-016  

**Description**: Check if specific file would be indexed

**Tasks**:
1. Add check-file subcommand
2. Parameter: file path
3. Run PathValidator
4. Print validation result with reason

**Acceptance Criteria**:
- Shows whether file included
- Explains why/why not
- Helpful for debugging

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/cli/CheckFileCommand.kt`

---

### CONTEXT-071: CLI - project-stats
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-017  

**Description**: Show project boundary statistics

**Tasks**:
1. Add project-stats subcommand
2. Scan directories
3. Categorize files (indexable, excluded, etc.)
4. Print comprehensive stats
5. Show by watch path, by language, etc.

**Acceptance Criteria**:
- Comprehensive statistics
- Well-formatted output
- Useful for understanding project

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/cli/ProjectStatsCommand.kt`

---

### CONTEXT-072: CLI - rebuild
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-065  

**Description**: CLI wrapper for rebuild_context tool

**Tasks**:
1. Add rebuild subcommand
2. Options: --force, --paths, --dry-run, --no-vacuum
3. Interactive confirmation if not --force
4. Call RebuildContextTool
5. Show progress if sync

**Acceptance Criteria**:
- CLI convenient for manual rebuilds
- Matches tool functionality
- Good UX

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/cli/RebuildCommand.kt`

---

### CONTEXT-073: CLI - Main Entry Point
**Priority**: P1  
**Size**: Small (2h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-068 through CONTEXT-072  

**Description**: Context CLI main entry point

**Tasks**:
1. Create contextd main class
2. Register all subcommands
3. Use Clikt or similar CLI library
4. Help text
5. Version info

**Acceptance Criteria**:
- All commands accessible
- Help text useful
- Good CLI UX

**Files to Create**:
- `src/main/kotlin/com/orchestrator/context/cli/ContextCli.kt`

---

## Phase 11: Testing (Week 15)

### CONTEXT-074: Unit Tests - Chunkers
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-021 through CONTEXT-026  

**Description**: Test all chunker implementations

**Tasks**:
1. Create test fixtures for each language
2. Test chunking boundaries
3. Verify labels extracted
4. Verify chunk kinds
5. Check token estimates
6. Edge cases (empty files, huge files)

**Acceptance Criteria**:
- All chunkers tested
- Edge cases covered
- Fixtures comprehensive

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/chunking/MarkdownChunkerTest.kt`
- `src/test/kotlin/com/orchestrator/context/chunking/PythonChunkerTest.kt`
- `src/test/kotlin/com/orchestrator/context/chunking/TypeScriptChunkerTest.kt`
- etc.

---

### CONTEXT-075: Unit Tests - Providers
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-048, CONTEXT-050, CONTEXT-051  

**Description**: Test provider implementations

**Tasks**:
1. Mock embedding API
2. Test query accuracy
3. Test scope filtering
4. Test score thresholds
5. Test error handling

**Acceptance Criteria**:
- Providers return correct results
- Filters work
- Errors handled

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/providers/SemanticContextProviderTest.kt`
- `src/test/kotlin/com/orchestrator/context/providers/SymbolContextProviderTest.kt`
- etc.

---

### CONTEXT-076: Unit Tests - MMR Re-ranker
**Priority**: P0  
**Size**: Small (2h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-033  

**Description**: Test MMR algorithm

**Tasks**:
1. Create test cases with known results
2. Test diversity vs relevance tradeoff
3. Test budget exhaustion
4. Test edge cases (empty, single result)

**Acceptance Criteria**:
- MMR algorithm correct
- Lambda parameter works
- Budget respected

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/search/MmrRerankerTest.kt`

---

### CONTEXT-077: Integration Test - Bootstrap
**Priority**: P0  
**Size**: Medium (4h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-045  

**Description**: End-to-end bootstrap test

**Tasks**:
1. Create test project (100 files)
2. Run bootstrap
3. Verify all files indexed
4. Verify chunks created
5. Verify embeddings generated
6. Verify database state

**Acceptance Criteria**:
- Bootstrap completes
- All files processed
- Database correct

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/integration/BootstrapIntegrationTest.kt`

---

### CONTEXT-078: Integration Test - Incremental Update
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-039, CONTEXT-042  

**Description**: Test incremental indexing

**Tasks**:
1. Index initial project
2. Modify file
3. Verify auto-refresh
4. Query for updated content
5. Verify results include new content

**Acceptance Criteria**:
- Changes detected
- Re-indexing triggered
- Queries return updated results

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/integration/IncrementalUpdateTest.kt`

---

### CONTEXT-079: Integration Test - Rebuild
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-065  

**Description**: Test full rebuild workflow

**Tasks**:
1. Index project
2. Trigger rebuild (validateOnly)
3. Verify dry-run report
4. Trigger full rebuild
5. Poll status
6. Verify completion
7. Verify database correct

**Acceptance Criteria**:
- Dry-run works
- Full rebuild works
- Status tracking accurate

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/integration/RebuildIntegrationTest.kt`

---

### CONTEXT-080: Integration Test - Query Workflow
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-058, CONTEXT-062  

**Description**: Test context query end-to-end

**Tasks**:
1. Index test project
2. Query via ContextModule
3. Verify relevant results returned
4. Verify MMR diversity
5. Verify budget respected
6. Query via MCP tool
7. Verify same results

**Acceptance Criteria**:
- Queries return relevant results
- Diversity algorithm works
- Budget enforced
- MCP tool works

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/integration/QueryWorkflowTest.kt`

---

### CONTEXT-081: Performance Test - Indexing
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-045  

**Description**: Measure indexing throughput

**Tasks**:
1. Generate synthetic project (10k files, 2M lines)
2. Measure bootstrap time
3. Verify ≥3k lines/sec
4. Measure incremental update time
5. Profile bottlenecks

**Acceptance Criteria**:
- Meets throughput target
- Performance metrics collected
- Bottlenecks identified

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/performance/IndexingPerformanceTest.kt`

---

### CONTEXT-082: Performance Test - Query Latency
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Claude Code  
**Dependencies**: CONTEXT-058  

**Description**: Measure query performance

**Tasks**:
1. Index 10k chunks
2. Run 100 random queries
3. Measure p50, p95, p99 latency
4. Verify p95 ≤250ms
5. Profile slow queries

**Acceptance Criteria**:
- Meets latency target
- Performance consistent
- Profiling data collected

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/performance/QueryPerformanceTest.kt`

---

### CONTEXT-083: Golden Query Tests
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Codex CLI  
**Dependencies**: CONTEXT-058  

**Description**: Predefined query/result pairs

**Tasks**:
1. Define 10 golden queries with expected results
2. Examples:
   - "How do we validate JWT?" → JwtValidator.kt
   - "Database schema for users" → User table DDL
   - "Recent authentication changes" → auth commits
3. Index test project
4. Run all golden queries
5. Verify top-3 contain expected files
6. Verify relevance scores >0.7

**Acceptance Criteria**:
- All golden queries pass
- Results relevant
- Scores above threshold

**Files to Create**:
- `src/test/kotlin/com/orchestrator/context/integration/GoldenQueryTest.kt`

---

## Phase 12: Documentation & Deployment (Week 16)

### CONTEXT-084: Documentation - README
**Priority**: P0  
**Size**: Medium (3h)  
**Assigned To**: Either  
**Dependencies**: All previous  

**Description**: Comprehensive README

**Tasks**:
1. Update README.md with:
   - Project overview
   - Quick start guide
   - Installation
   - Configuration
   - Usage examples
   - CLI reference
   - Troubleshooting
   - Architecture link

**Acceptance Criteria**:
- Clear instructions
- Examples work
- Covers all features

**Files to Update**:
- `README.md`

---

### CONTEXT-085: Documentation - API Reference
**Priority**: P1  
**Size**: Medium (3h)  
**Assigned To**: Either  
**Dependencies**: CONTEXT-062 through CONTEXT-066  

**Description**: MCP API documentation

**Tasks**:
1. Create API_REFERENCE.md
2. Document all MCP tools:
   - query_context
   - refresh_context
   - get_context_stats
   - rebuild_context
   - get_rebuild_status
3. Include:
   - Parameters
   - Response schemas
   - Examples
   - Error codes

**Acceptance Criteria**:
- All tools documented
- Examples provided
- Easy to follow

**Files to Create**:
- `docs/CONTEXT_API_REFERENCE.md`

---

## Summary

**Phase 0 (Week 1)**: 10 tasks - Database schema, domain models, configuration
**Phase 1 (Week 2)**: 8 tasks - Project boundaries, file discovery, filtering
**Phase 2 (Week 3-4)**: 10 tasks - Chunking strategies for all languages
**Phase 3 (Week 5)**: 5 tasks - Embedding and vector search
**Phase 4 (Week 6)**: 6 tasks - Indexing pipeline
**Phase 5 (Week 7)**: 3 tasks - File watching daemon
**Phase 6 (Week 8)**: 4 tasks - Bootstrap orchestration
**Phase 7 (Week 9-10)**: 9 tasks - Provider implementations
**Phase 8 (Week 11-12)**: 6 tasks - Orchestrator integration
**Phase 9 (Week 13)**: 6 tasks - MCP tools
**Phase 10 (Week 14)**: 6 tasks - CLI commands
**Phase 11 (Week 15)**: 10 tasks - Testing
**Phase 12 (Week 16)**: 2 tasks - Documentation

**Total**: 85 tasks over 16 weeks

---

## Task Assignment Strategy

**Codex CLI** (Architecture/Planning):
- CONTEXT-011, CONTEXT-016, CONTEXT-018, CONTEXT-019, CONTEXT-027
- CONTEXT-039, CONTEXT-042, CONTEXT-045, CONTEXT-047, CONTEXT-054
- CONTEXT-055, CONTEXT-058, CONTEXT-060, CONTEXT-065, CONTEXT-073
- CONTEXT-081, CONTEXT-083

**Claude Code** (Implementation):
- All remaining tasks (majority of implementation work)

**Either**:
- CONTEXT-084, CONTEXT-085 (documentation)

---

## Dependencies Graph

Each task lists dependencies - must be completed in order within phases, but phases can overlap:
- Phase 0 completes before starting Phase 1
- Phase 1 completes before starting Phase 2
- Phases 2-3 can overlap (chunking + embedding)
- Phases 4-5-6 sequential (indexing → watching → bootstrap)
- Phases 7-8 can overlap (providers + orchestrator)
- Phases 9-10-11-12 sequential (tools → CLI → tests → docs)

---

## Risk Mitigation

**High-Risk Tasks** (may take longer):
- CONTEXT-022: Python Chunker (AST parsing complexity)
- CONTEXT-023: TypeScript Chunker (Babel/tree-sitter integration)
- CONTEXT-030: Local Embedder (Python/ONNX integration)
- CONTEXT-045: Bootstrap Orchestrator (complex orchestration)
- CONTEXT-049: Symbol Index Builder (tree-sitter complexity)
- CONTEXT-054: Hybrid Provider (multi-provider coordination)

**Mitigation**: Allocate buffer time, early prototyping, fallback options

---

## Acceptance Criteria (Overall)

✅ All 85 tasks completed
✅ All tests passing (unit + integration + performance)
✅ Performance targets met (3k lines/sec indexing, <250ms queries)
✅ Token reduction ≥80% demonstrated
✅ Documentation complete
✅ CLI functional
✅ MCP tools working
✅ Zero breaking changes to orchestrator

---

**End of Implementation Plan**
