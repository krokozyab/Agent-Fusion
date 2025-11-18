# Phase 1: Search Quality Improvements

**Released**: 2024-11-13  
**Impact**: Improved search relevance, diversity, and context coherence

---

## What's New

### 🎯 MMR Reranking
Maximal Marginal Relevance algorithm reduces redundant results and improves diversity.

**Benefits**:
- Less repetitive search results
- Better coverage of different aspects
- Configurable relevance vs diversity tradeoff

**Config**: `use_optimizer_in_tool = true` (default)

---

### 🔗 Neighbor Expansion
Automatically includes surrounding code chunks for better context.

**Benefits**:
- See code before/after matches
- Better understanding of context
- More complete code snippets

**Config**: `neighbor_window = 1` (default: ±1 chunk)

---

### ⚡ Path & Language Boosts
Smart scoring based on file location and programming language.

**Benefits**:
- Prioritizes main source code over tests
- Boosts relevant languages (26 supported)
- Penalizes vendor/dependency code

**Config**: 
```toml
[context.query.boosts.path_prefixes]
"src/main" = 1.05
"src/test" = 0.95

[context.query.boosts.languages]
kotlin = 1.02
python = 1.02
json = 0.95
```

---

### 📊 IDF Scoring
Inverse Document Frequency boosts rare technical terms.

**Benefits**:
- Rare terms (e.g., "authentication") get higher scores
- Common terms (e.g., "the") get lower scores
- Better relevance for technical queries

**Config**: `idf_enabled = true` (default)

---

## Performance

- **Latency**: +10-20ms typical (acceptable overhead)
- **Quality**: Significantly improved relevance
- **Backward Compatible**: All features can be disabled

---

## Rollback

Disable all features:
```toml
[context.query]
use_optimizer_in_tool = false
neighbor_window = 0
idf_enabled = false

[context.query.boosts.path_prefixes]
# Empty = disabled

[context.query.boosts.languages]
# Empty = disabled
```

---

## Technical Details

- **26 tests added** (all passing)
- **11 new files** created
- **4 files** modified
- **Zero breaking changes**
