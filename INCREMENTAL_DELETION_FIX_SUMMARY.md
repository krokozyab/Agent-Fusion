# Incremental File Deletion Detection Fix

**Commit**: `f4e4e7c` - "Fix: Enable incremental deletion detection in file watcher"

## Problem Statement

File deletion events were being **silently ignored** by the file watcher, causing:

1. **Deleted files remained indexed indefinitely** until a full reindex
2. **Deletion artifacts weren't cleaned up incrementally**
3. **Only full reindex (slow operation) would remove deleted files** from the index
4. **No real-time cleanup** of deleted file metadata

### Why It Was Happening

The `WatcherDaemon.processFileEvent()` method was applying the same validation logic to all file events (CREATE, MODIFY, DELETE):

```kotlin
val validation = pathValidator.validate(event.path)  // ← Fails for deleted files!
if (!validation.valid) return  // ← Silent failure for DELETE events
```

Since deleted files **no longer exist on disk**, the `pathValidator.validate()` call would fail and the event would be discarded.

## Solution

**Skip validation for DELETE events** since:
1. The file no longer exists, so validation would always fail
2. `ChangeDetector` handles missing files correctly as deletions
3. `IncrementalIndexer` properly removes artifacts for deleted files

### Implementation

Modified `WatcherDaemon.processFileEvent()`:

```kotlin
private suspend fun processFileEvent(event: FileWatchEvent) {
    if (event.isDirectory) return

    // DELETE events should bypass validation since file no longer exists
    if (event.kind == FileWatchEvent.Kind.DELETED) {
        log.debug("Enqueuing delete event for {} (skipping validation)", event.path)
        enqueue(event.path)
        return
    }

    // For CREATE/MODIFY events, validate normally
    val validation = pathValidator.validate(event.path)
    if (!validation.valid) {
        // Handle validation failure...
        return
    }
    enqueue(event.path)
}
```

## How It Works Now

### Incremental Deletion Flow

```
1. User deletes file from disk
   ↓
2. FileWatcher detects ENTRY_DELETE event
   ↓
3. WatcherDaemon receives event
   ↓
4. processFileEvent() checks kind == DELETED
   ↓
5. Event enqueued DIRECTLY (no validation)
   ↓
6. enqueue() adds path to pendingPaths
   ↓
7. flushPending() batches the paths
   ↓
8. processBatch() calls incrementalIndexer.updateAsync()
   ↓
9. ChangeDetector detects missing file as DELETED
   ↓
10. IncrementalIndexer removes database artifacts
    (chunks, embeddings, file_state records)
```

### Results

✅ **Immediate cleanup** - Deleted files are removed from index within 1 second (batch window)
✅ **No validation overhead** - DELETE events bypass unnecessary validation
✅ **Consistent behavior** - All file events (CREATE, MODIFY, DELETE) processed incrementally
✅ **Log clarity** - Debug logs show "delete event" processing path

## Technical Details

### File Modified

- `src/main/kotlin/com/orchestrator/context/watcher/WatcherDaemon.kt`
  - `processFileEvent()` method (lines 189-219)

### Components Involved

1. **FileWatcher** - Detects filesystem events via `WatchService`
2. **WatcherDaemon** - Listens for events and validates/enqueues them
3. **ChangeDetector** - Compares filesystem state with persisted state
4. **IncrementalIndexer** - Re-indexes changed files and removes deleted ones

## Testing

### Manual Test

```bash
# Create test file
echo "test content" > /tmp/test_file.md

# Wait for watcher to pick it up
sleep 2

# Query context to verify indexing
curl -X POST http://127.0.0.1:3000/mcp/json-rpc \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query_context","query":"test content"}}'

# Delete file
rm /tmp/test_file.md

# Wait for incremental deletion processing
sleep 2

# File artifacts should now be removed from index
```

### Expected Behavior

**Before Fix:**
- File deleted from disk
- Remains in index indefinitely
- Only removed on full reindex (via web UI)

**After Fix:**
- File deleted from disk
- Watcher detects ENTRY_DELETE
- Within ~1 second: chunks, embeddings, and metadata removed from database
- Next `query_context` won't return deleted files

## Performance Impact

- **Negligible overhead** - One additional condition check per file event
- **Reduces database bloat** - No accumulation of deleted file artifacts
- **Improves index freshness** - Immediate deletion detection

## Edge Cases Handled

✅ **File no longer exists** - ChangeDetector checks if file exists
✅ **Multiple watch roots** - Deletion events properly routed to correct root
✅ **Batch processing** - Delete events batched with other changes
✅ **Validation failures** - No special handling needed since we skip validation

## Backward Compatibility

✅ **No breaking changes** - Only internal behavior affected
✅ **Database schema unchanged** - Uses existing deletion mechanisms
✅ **API unchanged** - All public interfaces remain the same

## Future Improvements

1. **Implicit deletion detection** - Optional periodic scan for files indexed but no longer on disk
2. **Deletion reporting** - Webhooks/events on file deletion
3. **Deletion history** - Track when files were deleted for audit purposes
4. **Batch optimization** - Combine delete events across multiple files

## Summary

This fix ensures that **file deletions are processed incrementally** just like file creation and modification events, eliminating the need for full reindexing to clean up deleted files from the context database.

**Result**: Real-time, automatic cleanup of deleted file artifacts with zero manual intervention required.
