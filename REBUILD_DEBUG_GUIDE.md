# Index Rebuild Debugging Guide

## Issue: Rebuild Only Finds 1 File Instead of Expected 500+

### Root Causes to Check

1. **Wrong Working Directory**
   - The rebuild uses `System.getProperty("user.dir")` as the default path
   - This might not be the project root
   - **Fix**: Check your application's working directory when it starts

2. **Ignore Patterns Filtering Out Files**
   - The `.contextignore` and `.gitignore` files can filter too aggressively
   - Build directories might contain actual source files you want indexed
   - **Fix**: Review `.contextignore` and `.gitignore` patterns

3. **Extension Filtering**
   - Only files with allowed extensions are indexed
   - Default: `.kt`, `.java`, `.py`, `.ts`, `.tsx`, `.js`, `.jsx`, `.json`, `.md`, `.yaml`, `.yml`, `.csv`, `.txt`, `.docx`
   - **Fix**: Check if your files have these extensions

4. **File Size Limits**
   - Files larger than `max_file_size_mb` (200MB) are skipped
   - Files larger than `warn_file_size_mb` (10MB) generate warnings
   - **Fix**: Check logs for skipped files due to size

### Debugging Steps

#### Step 1: Check Working Directory and Startup Logs
When the application starts, look for this log line:
```
INFO  c.o.mcp.tools.RebuildContextTool [] - Starting context rebuild for X paths
```

Check what path is being used. If it shows a temp directory instead of your project root, that's the issue.

#### Step 2: Enable Debug Logging
Add to your `src/main/resources/logback.xml`:
```xml
<logger name="com.orchestrator.context" level="DEBUG"/>
<logger name="com.orchestrator.mcp.tools.RebuildContextTool" level="DEBUG"/>
```

This will show:
- Exact paths being scanned
- Files being processed
- Files being filtered out (and why)
- Errors during indexing

#### Step 3: Check Bootstrap Logs
Look for these log messages:
```
Discovered X files in bootstrap
Indexed X files successfully
Failed to index Y files
```

The "Failed to index" count tells you if files are failing to process.

#### Step 4: Run Rebuild with Monitoring
1. Click "Rebuild Index" button
2. Watch the progress bar in the UI
3. Check logs for:
   - Which files are being processed
   - Which files are being skipped (and why)
   - Any error messages

#### Step 5: Manual Test (Command Line)
You can test file discovery without rebuilding by checking:
```bash
find /path/to/project -type f \( -name "*.kt" -o -name "*.java" -o -name "*.py" \) | grep -v ".git\|node_modules\|build\|dist" | wc -l
```

This shows how many files should theoretically be indexed.

### Configuration Changes Made

The following files have been updated to improve rebuild behavior:

1. **config/context.toml** - Added explicit watcher and bootstrap configuration
2. **.contextignore** - Created to filter out test artifacts and build directories

### What Happens During Rebuild

1. ✅ HTTP request arrives with rebuild command
2. ✅ Backend launches async rebuild job
3. ✅ Rebuild job clears old index
4. ✅ Bootstrap runs to discover files
5. ✅ Each file is processed and indexed
6. ✅ Progress events sent via SSE to UI
7. ✅ Summary published when complete

### Monitoring Progress

The rebuild publishes progress events every second via SSE. In the browser console, you should see SSE messages like:
```javascript
Event: indexProgress
Data: {
  "operationId": "rebuild-xxx",
  "percentage": 45,
  "processed": 150,
  "total": 500,
  "message": "Rebuilding..."
}
```

### Expected Behavior After Fix

When you click "Rebuild Index":
1. Progress bar appears and starts moving
2. File count increases as files are processed
3. Completion status shows when finished
4. Index is fully repopulated with all project files

### If Still Having Issues

1. **Check logs** for "Discovered X files" message
2. **Verify working directory** - should be project root
3. **Check ignore patterns** - might be filtering too much
4. **Verify file extensions** - are your files in the allowed list?
5. **Monitor memory** - large projects might need more memory

### Next Steps if Not Working

If rebuilding still only finds 1 file:

1. Add more detailed logging to see file discovery
2. Check if the working directory is correct
3. Verify ignore patterns aren't too aggressive
4. Run manual file discovery command (see Step 5 above)
5. Check application startup output for configuration errors
