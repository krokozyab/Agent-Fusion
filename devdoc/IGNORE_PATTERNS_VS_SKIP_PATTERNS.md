# ignore_patterns vs skip_patterns: Architecture & Wiring

## Quick Summary

| Aspect | `ignore_patterns` | `skip_patterns` |
|--------|------------------|-----------------|
| **Config Section** | `[context.watcher]` | `[context.indexing]` |
| **Purpose** | File watching phase | Indexing phase |
| **Matcher Type** | Regex (via PathFilter) | Glob patterns (via SkipFilter) |
| **Can be merged?** | ❌ NO - Different phases | ❌ NO - Different matching logic |
| **Also includes** | .gitignore, .contextignore, .dockerignore | N/A |
| **Pattern style** | Flexible regex | Intuitive glob patterns |

## Architecture Diagram

```
File System Event
       ↓
   [WATCHER PHASE]
   ignorePatterns (PathFilter + .gitignore/.contextignore)
       ↓
   [DISCOVERY PHASE]
   includePaths (whitelist)
       ↓
   [INDEXING PHASE]
   ├─ Extension Filter (allowed_extensions)
   ├─ skipPatterns (SkipFilter - glob patterns)
   ├─ Binary Detection
   └─ Size Limits
       ↓
   Chunks → Embeddings → Database
```

## Detailed Comparison

### 1. ignore_patterns (Watcher Phase)

**Config Location**: `[context.watcher]`

```toml
[context.watcher]
ignore_patterns = [
    ".kotlin/",      # Exclude .kotlin/ directory entirely
    "build/",
    "node_modules/",
    ".git/"
]
use_gitignore = true          # Also respects .gitignore
use_contextignore = false     # Respects .contextignore if enabled
```

**Implementation**: `PathFilter` class in `src/main/kotlin/com/orchestrator/context/discovery/PathFilter.kt`

```kotlin
class PathFilter private constructor(
    private val matchers: List<Regex>,  // Regex matchers!
    private val caseInsensitive: Boolean
) {
    fun shouldIgnore(path: Path): Boolean {
        val absolute = normalize(path.toAbsolutePath().normalize().toString())
        val relative = path.fileName?.let { normalize(it.toString()) }
        return matchers.any { regex ->
            regex.matches(absolute) || (relative != null && regex.matches(relative))
        }
    }
}
```

**Key Characteristics**:
- **Regex-based**: Patterns are converted to regex matchers
- **Aggregates multiple sources**: Config patterns + .gitignore + .contextignore + .dockerignore
- **Early filtering**: Applied during file watcher startup scan
- **Used by**: FileWatcher, WatcherDaemon, FilesystemSnapshotCalculator, RebuildContextCli
- **Purpose**: Prevent watcher from monitoring unnecessary directories

### 2. skip_patterns (Indexing Phase)

**Config Location**: `[context.indexing]`

```toml
[context.indexing]
skip_patterns = [
    "*.min.js",           # Skip minified JS (glob pattern)
    "*.min.css",          # Skip minified CSS
    "*.test.ts",          # Skip test files
    "**/dist/**",         # Skip dist directories
    "**/node_modules/**"  # Skip node_modules
]
```

**Implementation**: `SkipFilter` class in `src/main/kotlin/com/orchestrator/context/discovery/SkipFilter.kt`

```kotlin
class SkipFilter private constructor(
    private val matchers: List<GlobMatcher>  // Glob matchers!
) {
    fun shouldSkip(path: Path): Boolean {
        return matchers.any { matcher ->
            if (!matcher.isAbsolutePattern) {
                // Simple patterns: match filename only (e.g., "*.min.js")
                val fileNameMatcher = FileSystems.getDefault().getPathMatcher("glob:${matcher.pattern}")
                fileNameMatcher.matches(path.fileName ?: return@any false)
            } else {
                // Absolute patterns: match full path (e.g., "**/dist/**")
                val pathMatcher = FileSystems.getDefault().getPathMatcher("glob:${matcher.pattern}")
                pathMatcher.matches(path)
            }
        }
    }
}
```

**Key Characteristics**:
- **Glob-based**: More intuitive than regex
- **Two-mode matching**:
  - Simple patterns (no `/` or `**`): Match against filename only
  - Absolute patterns (contain `/` or `**`): Match against full path
- **Late filtering**: Applied AFTER extension filtering during indexing
- **Used by**: PathValidator during actual indexing
- **Purpose**: Fine-grained control after extension filtering

### 3. Optional: ignore.patterns (Bootstrap/Ignore Config)

**Config Location**: `[ignore]`

```toml
[ignore]
patterns = [
    "build/",
    "dist/",
    ".gradle/",
    ".kotlin/",      # Can also go here
    "node_modules/",
    # ... many more ...
]
```

**Purpose**: Legacy configuration section that mirrors `[context.watcher].ignore_patterns`. Currently less used than the primary sections.

## Processing Pipeline

```
┌─────────────────────────────────────────────────────────────────┐
│ FILE DISCOVERY                                                   │
└─────────────────────────────────────────────────────────────────┘
           ↓
┌─────────────────────────────────────────────────────────────────┐
│ [WATCHER PHASE] - Triggered on file system changes              │
│                                                                   │
│  1. PathFilter.shouldIgnore(path)                               │
│     - Checks: ignorePatterns (regex) + .gitignore + others      │
│     - Rejects: .git/, build/, node_modules/, .kotlin/, etc.    │
│                                                                   │
│  2. IncludePathsFilter.shouldInclude(path)  [if configured]     │
│     - Whitelist check: only index if in includePaths            │
│                                                                   │
│  Result: "watchable files" passed to indexer                   │
└─────────────────────────────────────────────────────────────────┘
           ↓
┌─────────────────────────────────────────────────────────────────┐
│ [INDEXING PHASE] - When files are actually indexed              │
│                                                                   │
│  1. ExtensionFilter.shouldInclude(path)                         │
│     - Checks: allowed_extensions list (.kt, .py, .ts, etc.)     │
│     - Rejects: .exe, .jar, .zip, etc.                          │
│                                                                   │
│  2. SkipFilter.shouldSkip(path)       [AFTER extension check]   │
│     - Checks: skip_patterns (glob) such as *.min.js             │
│     - Rejects: .test.ts, .spec.js, *.min.css, etc.             │
│                                                                   │
│  3. BinaryDetector.isBinary(path)                               │
│     - Detects: Binary files (.pdf, .jpg, .exe, etc.)           │
│                                                                   │
│  4. Size limits                                                  │
│     - Rejects: Files > maxFileSizeMb                            │
│                                                                   │
│  Result: "indexable files" passed to chunker                   │
└─────────────────────────────────────────────────────────────────┘
           ↓
┌─────────────────────────────────────────────────────────────────┐
│ CHUNKING, EMBEDDING, STORAGE                                    │
└─────────────────────────────────────────────────────────────────┘
```

## Code Wiring Examples

### Example 1: WatcherDaemon - Uses ignorePatterns

```kotlin
// src/main/kotlin/com/orchestrator/context/watcher/WatcherDaemon.kt
class WatcherDaemon(...) {
    private val pathFilter = PathFilter.fromSources(
        root,
        configPatterns = watcherConfig.ignorePatterns,  // ← ignore_patterns
        includeGitignore = watcherConfig.useGitignore,
        includeContextignore = watcherConfig.useContextignore
    )

    private fun processFileChanges(changes: List<Path>) {
        changes.filter { path ->
            !pathFilter.shouldIgnore(path)  // ← Filters using ignore_patterns
        }.forEach { path ->
            indexing.scheduleForIndexing(path)
        }
    }
}
```

### Example 2: PathValidator - Uses Both Filters

```kotlin
// src/main/kotlin/com/orchestrator/context/discovery/PathValidator.kt
class PathValidator(...) {
    private val skipFilter: SkipFilter = SkipFilter.fromPatterns(
        indexingConfig.skipPatterns  // ← skip_patterns
    )

    fun validate(path: Path): ValidationResult {
        // Check extension first
        if (!isAllowedExtension(path)) {
            return invalid(Reason.EXTENSION_NOT_ALLOWED, ...)
        }

        // Check skip patterns AFTER extension matching
        if (isSkippedByPattern(path)) {  // ← Uses skip_patterns
            return invalid(Reason.SKIPPED_BY_PATTERN, ...)
        }

        return ValidationResult.Valid
    }
}
```

### Example 3: FilesystemSnapshotCalculator - Uses ignorePatterns

```kotlin
// src/main/kotlin/com/orchestrator/web/services/FilesystemSnapshotCalculator.kt
class FilesystemSnapshotCalculator(...) {
    private val pathFilter = PathFilter.fromSources(
        root,
        configPatterns = contextConfig.watcher.ignorePatterns,  // ← ignore_patterns
        includeGitignore = contextConfig.watcher.useGitignore
    )

    fun scanDirectory(dir: Path): FilesystemSnapshot {
        val newFiles = Files.walk(dir)
            .filter { !pathFilter.shouldIgnore(it) }  // ← Filters out ignored paths
            .toList()
    }
}
```

## Can They Be Merged?

**Short Answer: NO** ❌

### Reasons:

1. **Different phases**:
   - `ignore_patterns` operates during watching phase (before files are selected)
   - `skip_patterns` operates during indexing phase (after extension filtering)

2. **Different matchers**:
   - `ignore_patterns` uses regex (more powerful but less intuitive)
   - `skip_patterns` uses glob patterns (intuitive and Unix-standard)

3. **Different semantics**:
   - `ignore_patterns` respects ignore files (.gitignore, .contextignore)
   - `skip_patterns` doesn't integrate with ignore files

4. **Different purposes**:
   - `ignore_patterns` prevents watcher from monitoring directories
   - `skip_patterns` fine-tunes which watched files get indexed

### Example: Why Separation Matters

For `.kotlin/metadata/` files:

```toml
# ✅ CORRECT: Put in ignore_patterns
[context.watcher]
ignore_patterns = [".kotlin/"]  # Watcher won't monitor this directory

# ❌ Would NOT work well in skip_patterns:
[context.indexing]
skip_patterns = [".kotlin/"]  # Watcher still monitors it, then indexer skips - inefficient!
```

## Recommendations

### When to Use ignore_patterns

- **Large directories** to exclude from watching (e.g., `build/`, `.git/`, `node_modules/`)
- **Directory-level exclusions** (entire folder hierarchies)
- **Integration with VCS**: Automatically respect `.gitignore`

**Example config**:
```toml
[context.watcher]
ignore_patterns = [
    ".git/",
    ".gradle/",
    ".kotlin/",
    "build/",
    "dist/",
    "node_modules/",
    "target/",
    ".idea/",
    ".vscode/"
]
```

### When to Use skip_patterns

- **File-level filtering** after extensions are checked
- **Specific file patterns** like test files, minified files
- **Fine-grained control** that doesn't affect watcher

**Example config**:
```toml
[context.indexing]
skip_patterns = [
    "*.min.js",           # Minified files
    "*.min.css",
    "*.test.ts",          # Test files
    "*.spec.js",
    "**/dist/**",         # Build output
    "**/coverage/**",     # Coverage reports
]
```

## Current State (After Recent Changes)

After the CV search optimization, the configuration now correctly uses both:

```toml
# WATCHER PHASE - prevents monitoring .kotlin/metadata/
[context.watcher]
ignore_patterns = [".kotlin/"]  ← Added to prevent watcher overhead

# INDEXING PHASE - fine-grained control
[context.indexing]
skip_patterns = [
    "*.min.js",
    "*.min.css",
    "*.test.ts",
    "**/dist/**",
    "**/node_modules/**"
]
```

This dual-layer approach provides:
- **Efficiency**: Watcher doesn't monitor auto-generated build files
- **Flexibility**: Indexer still filters test files, minified files, etc.
- **Clarity**: Each section has a clear purpose

## References

- **PathFilter**: `src/main/kotlin/com/orchestrator/context/discovery/PathFilter.kt`
- **SkipFilter**: `src/main/kotlin/com/orchestrator/context/discovery/SkipFilter.kt`
- **PathValidator**: `src/main/kotlin/com/orchestrator/context/discovery/PathValidator.kt`
- **WatcherDaemon**: `src/main/kotlin/com/orchestrator/context/watcher/WatcherDaemon.kt`
- **ContextConfig**: `src/main/kotlin/com/orchestrator/context/config/ContextConfig.kt`
