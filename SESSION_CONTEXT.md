# Session Context - 2025-10-17

## Current Issue: CSV Files Not Being Indexed

**Problem**: `query_context` MCP tool fails to find information in CSV files despite `.csv` being in `allowed_extensions`.

---

## What We Were Working On

Investigating why `query_context` MCP tool failed to find "Vendor Original System Reference" information.

---

## Key Findings

### 1. The Information Was Found (Using Grep)
- **Location**: `datafusing_cloudsql_report.csv:365`
- **Column Name**: `VENDOR_EXT_REF`
- **Short Name**: `VENDOR_ORIG_SYS_REF`
- **Description**: "Vendor Original System Reference for this vendor profile record. Used to lookup VENDOR_ID."
- **Type**: VARCHAR(30), Nullable, Active status

### 2. Root Cause - CSV File Not Indexed
The `query_context` tool returned 0 results because:
- The CSV file `datafusing_cloudsql_report.csv` is **not indexed** in the context database
- Git status shows it as untracked (`??`)
- Query to `file_state` table confirmed: **0 CSV files in the database**
- Context stats show only 413 files: Kotlin (371), Markdown (33), YAML (5), TypeScript (4)
- **No CSV or TXT files indexed** despite being in `allowed_extensions`

### 3. Configuration vs Reality Gap
- **Config file**: `config/context.toml`
- **Allowed extensions**: `.kt`, `.kts`, `.java`, `.py`, `.ts`, `.tsx`, `.md`, `.yaml`, `.yml`, `.csv`, `.txt`
- **Problem**: CSV and TXT are configured but NOT currently indexed
- **Database verification**:
  ```bash
  duckdb context.duckdb "SELECT rel_path FROM file_state WHERE rel_path LIKE '%.csv'"
  # Result: 0 rows
  ```

### 4. Current Context Database State
- **Total files**: 413
- **Total chunks**: 6,108
- **Total embeddings**: 6,108
- **Languages indexed**: Kotlin, Markdown, YAML, TypeScript only
- **Missing**: CSV and TXT files despite configuration

---

## CRITICAL ACTION REQUIRED WHEN YOU RETURN

### Step 1: Start the Orchestrator Server
The server is currently stopped.

### Step 2: Trigger Context Refresh
Run one of these options:

**Option A - Refresh (incremental, recommended)**
```
mcp__orchestrator__refresh_context(
    paths: ["/Users/sergeyrudenko/projects/codex_to_claude"],
    force: false,
    async: true,
    parallelism: 4
)
```

**Option B - Full Rebuild (if refresh doesn't work)**
```
mcp__orchestrator__rebuild_context(
    confirm: true,
    async: true,
    parallelism: 4
)
```

### Step 3: Verify CSV Files Are Indexed
After refresh completes, check:
```bash
duckdb context.duckdb "SELECT rel_path FROM file_state WHERE rel_path LIKE '%.csv'"
```
Should show `datafusing_cloudsql_report.csv` and any other CSV files.

### Step 4: Test Query Context Again
```
mcp__orchestrator__query_context(
    query: "Vendor Original System Reference vendor profile",
    k: 15
)
```
Should now return results from the CSV file at line 365.

---

## Expected Outcome
- CSV and TXT files will be indexed
- `query_context` will work for CSV content
- Language distribution will include CSV in stats
- Future searches will find vendor profile information

---

## Why This Matters
The context system configuration allows CSV files, but they're not being indexed. This means:
1. `query_context` can't find information in CSV files
2. Only fallback methods (Grep, manual search) work
3. Defeats the purpose of the semantic search capability

---

## Useful DuckDB Commands

```bash
# Check if server is running
ps aux | grep java | grep orchestrator

# Check CSV file indexing
duckdb context.duckdb "SELECT rel_path FROM file_state WHERE rel_path LIKE '%.csv'"

# Check all indexed languages
duckdb context.duckdb "SELECT language, COUNT(*) FROM file_state GROUP BY language"

# Verify chunks and embeddings count
duckdb context.duckdb "
SELECT
    (SELECT COUNT(*) FROM chunks) as total_chunks,
    (SELECT COUNT(*) FROM embeddings) as total_embeddings"
```

---

## Session End Status

**Date**: 2025-10-17
**Server Status**: Stopped (by user)
**Investigation Status**: Root cause identified ✅ - CSV files not indexed despite configuration
**Next Action**: Start server → Refresh context → Verify CSV indexing

---

## Key Takeaway for Next Session

**CSV and TXT files are configured in `allowed_extensions` but NOT being indexed. Need to refresh/rebuild context to pick up these file types.**

**Action Checklist:**
1. ✅ Start orchestrator server
2. ✅ Run `refresh_context` or `rebuild_context`
3. ✅ Verify CSV files appear in `file_state` table
4. ✅ Test `query_context` for vendor information
5. ✅ Check language distribution includes CSV
