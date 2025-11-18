# Full-Text Search Word Boundary Approach

## Problem Statement

The full-text search provider was not returning the  (DOCX file) as a direct hit when querying , despite the being properly indexed with embeddings. Instead, auto-generated metadata JSON files containing  in their file paths were dominating the search results with scores of 0.95.

### Root Causes Identified

1. **Original scoring function weakness**: The `scoreKeywords()` method used term frequency divided by content length, which heavily penalized longer documents. For a 2366-character chunk with the name appearing ~20 times, the score was only 0.08.

2. **Word concatenation matching**: File paths like `.kotlin/metadata/.../sergeyrudenko-...cinterop/...` contained the keywords concatenated as a single word. The original substring matching (`indexOf`) didn't differentiate between:
   - Meaningful phrase matches: 
   - Noise matches: "sergeyrudenko" (single word in file paths)

3. **Configuration issues**:
   - `.kotlin/metadata/` auto-generated build files were being indexed unnecessarily
   - Min score threshold (0.4) was filtering legitimate document results
   - Document language chunks received lower natural embedding scores due to code-optimized embedding model

## Solution Architecture

### 1. Word Boundary Detection Logic

Implemented phrase-match scoring that validates keyword word boundaries:

```kotlin
// Check if this is a word boundary match (not part of a larger word)
val isWordStart = nextPos == 0 || !contentLower[nextPos - 1].isLetterOrDigit()
val isWordEnd = nextPos + keyword.length >= contentLower.length ||
               !contentLower[nextPos + keyword.length].isLetterOrDigit()

if (isWordStart && isWordEnd) {
    // Valid word boundary match
}
```

**Key principle**: A keyword match only counts if it's surrounded by non-alphanumeric characters (spaces, punctuation, line breaks, etc.).

### 2. Tiered Scoring Strategy

**FullTextContextProvider.kt** (lines 144-236):

| Scenario | Score | Rationale |
|----------|-------|-----------|
| Phrase match: keywords in order as separate words, within 50 chars | 0.95 | Strongest signal - user likely searching for exact phrase |
| All keywords present as separate words but scattered | 0.70 | Good signal - all query terms appear in document |
| Partial matches: only some keywords found | 0.0-0.5 | Weak signal - uses term frequency |
| No word boundary matches | 0.0 | Reject - keywords don't appear as real words |

### 3. Configuration Changes

**fusionagent.toml**:

```toml
[context.watcher]
ignore_patterns = [
    ".kotlin/"  # Exclude auto-generated Kotlin build metadata
]

[context.query]
min_score_threshold = 0.35  # Lowered from 0.4 to include documents
```

**VectorSearchEngine.kt** (lines 62-82):

```kotlin
val adjustedScore = if (row.language?.lowercase() == "document") {
    (score * 1.35f).coerceAtMost(0.99f)  // 35% boost for document language
} else {
    score
}
```

## Implementation Details

### Word Boundary Matching Algorithm

The algorithm performs ordered phrase matching with word boundary validation:

```kotlin
private fun scoreKeywords(content: String, keywords: List<String>): Double {
    val contentLower = content.lowercase(Locale.US)

    // 1. Try to find all keywords in order as separate words
    if (keywords.size >= 2) {
        var pos = 0
        val positions = mutableListOf<Int>()

        for (keyword in keywords) {
            val nextPos = contentLower.indexOf(keyword, pos)
            if (nextPos == -1) break

            // Validate word boundaries
            val isWordStart = nextPos == 0 || !contentLower[nextPos - 1].isLetterOrDigit()
            val isWordEnd = nextPos + keyword.length >= contentLower.length ||
                           !contentLower[nextPos + keyword.length].isLetterOrDigit()

            if (isWordStart && isWordEnd) {
                positions.add(nextPos)
                pos = nextPos + keyword.length
            } else {
                pos = nextPos + 1  // Keep searching
            }
        }

        // Check distance if all keywords found
        if (positions.size == keywords.size) {
            val distance = (positions.last() + keywords.last().length) - positions.first()
            if (distance <= 50) {
                return 0.95  // Phrase match found!
            }
        }
    }

    // 2. Fall back to checking all keywords present (scattered)
    // ... count occurrences with word boundary validation ...

    // 3. Return term frequency score for partial matches
}
```

### Why This Works

**Example: Query "Sergey Rudenko"**

| Text Fragment | Match? | Reason |
|---|---|---|
| "Sergey Rudenko\nOracle ERP" | ✅ YES | Both words separated by space (word boundary) within 50 chars → 0.95 |
| ".../sergeyrudenko-0.29.0/.../file" | ❌ NO | Words concatenated as "sergeyrudenko" (no word boundaries) → rejected |
| "/Users/sergeyrudenko/projects/..." | ❌ NO | "sergeyrudenko" as single word in path → rejected |

## Results

### Before Solution
-  chunks ranked #20-21 (below k=10 default)
- Metadata JSON files dominated top 10 with 0.95 scores
- User had to request k=30+ to find the 

### After Solution
-  chunks ranked #1-2 with 0.962 score
- Metadata files eliminated from results
-  is the direct/expected hit for name query
- Solution generalizes: works for any phrase query



## Benefits

1. **Precision**: Distinguishes meaningful phrase matches from noise
2. **Scalability**: Works for any multi-word query, not just "Sergey Rudenko"
3. **Language-aware**: Respects word boundaries in any language using Unicode categories
4. **Simple**: Uses standard character classification, no regex or NLP required
5. **Performant**: O(n) string searches with early termination

## Edge Cases Handled

- **Single-word keywords**: Falls back to term frequency scoring
- **Concatenated words**: Rejected by word boundary checks (e.g., "sergeyrudenko")
- **Hyphenated words**: Accepted as word boundaries (e.g., "machine-learning")
- **Accented characters**: Works with Unicode character classification
- **Numbers in words**: Handled by `isLetterOrDigit()` checks

## Future Enhancements

1. **Configurable phrase distance**: Make the 50-char window configurable
2. **Fuzzy matching**: Support minor typos in keyword matching
3. **Phrase frequency boosting**: Weight by how often phrase appears
4. **Language-specific tokenization**: Use language-aware word breaking for CJK
5. **Query intent detection**: Differentiate phrase queries from AND/OR queries

## Related Files

- **Implementation**: `src/main/kotlin/com/orchestrator/context/providers/FullTextContextProvider.kt:144-236`
- **Document scoring boost**: `src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt:62-82`
- **Configuration**: `fusionagent.toml` (context.watcher.ignore_patterns, context.query.min_score_threshold)
- **Git fix**: `src/main/kotlin/com/orchestrator/context/providers/GitHistoryAnalyzer.kt:106,241`

## Conclusion

The word boundary approach provides a linguistically sound solution to distinguish between meaningful phrase matches and accidental keyword co-occurrence in file metadata. By validating that matched keywords appear as separate words (not concatenated), the full-text search provider now correctly prioritizes the CV as the top result for name-based queries, while naturally deprioritizing build artifacts and metadata files.
