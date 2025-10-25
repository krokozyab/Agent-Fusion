# Testing SSE Real-Time Updates

## Setup

### Step 1: Clean rebuild
The clean build has been running. Wait for it to complete, or if you want to start fresh:

```bash
./gradlew clean build --no-daemon
```

### Step 2: Start the application
```bash
./gradlew run
```

Wait for the server to start. You should see:
```
2025-10-25 XX:XX:XX.XXX  INFO  o.o.OrchestratorApplicationKt -
Orchestrator running at http://localhost:8081
```

### Step 3: Open the browser
Navigate to: `http://localhost:8081/index`

## Testing Steps

### Part 1: Verify Page Load
1. The page should load and display:
   - Current index status (Total Files, Indexed, Pending, Failed counts)
   - Progress indicator container (empty initially)
   - Admin actions button (Rebuild Index)

2. Open DevTools Console (F12) and look for:
   ```
   HTMX SSE extension loaded
   Creating EventSource
   ```

### Part 2: Start Rebuild
1. Click the "Rebuild Index" button
2. The page should respond immediately with a confirmation or action

### Part 3: Monitor Console for SSE Events
In the DevTools console, you should see messages like:

**When rebuild starts:**
```
SSE Message received - lastEvent: indexProgress eventType: undefined data length: XXXX
Found swap target for event: indexProgress
Performing swap for element: index-progress
```

**When rebuild completes:**
```
SSE Message received - lastEvent: indexSummary eventType: undefined data length: XXXX
Found swap target for event: indexSummary
Performing swap for element: index-summary
```

### Part 4: Verify UI Updates
1. **During rebuild:**
   - Progress bar should appear
   - Progress percentage should increase (0% → 100%)
   - File count should update

2. **After rebuild completes:**
   - Summary statistics should update automatically:
     - Total Files: Should match final count
     - Indexed: Should match indexed count
     - Pending: Should match pending count
     - Failed: Should show 0 or actual failures

### Part 5: Check for Errors
Look in the console for any errors like:
- "Cannot read properties of null"
- "Uncaught TypeError"
- "Failed to parse SSE"

**Expected result:** No errors related to SSE or DOM swapping

## Debugging Output Explained

The console will show detailed information about each SSE event:

```javascript
SSE Message received - lastEvent: indexProgress eventType: undefined data length: 1245
```
- **lastEvent**: The SSE event name (indexProgress, indexSummary, etc.)
- **eventType**: Additional event type info (usually undefined)
- **data length**: Size of HTML fragment being swapped

```javascript
Found swap target for event: indexProgress
```
- Successfully found the page element with `sse-swap="indexProgress"`

```javascript
Performing swap for element: index-progress
```
- About to replace the element's HTML with new content

```javascript
No swap target found for event: indexProgress available targets: ["indexSummary", "indexProgress"]
```
- Event received but no matching target element found
- Shows available targets for debugging

## If It's Not Working

### Issue 1: No console messages appearing
- The SSE connection might not be established
- Check if you see "Creating EventSource" message
- Check browser Network tab for `/sse/index` connection
- Look for WebSocket or EventSource connection status

### Issue 2: "No swap target found" message
- The page doesn't have an element with the matching `sse-swap` attribute
- This could indicate:
  - Page layout has changed
  - HTML generation isn't including sse-swap attributes
  - Check the Elements tab to verify: `<div sse-swap="indexProgress">`

### Issue 3: Swap not working even though target is found
- The swap might be failing silently
- Check for JavaScript errors in the console
- Verify the HTML fragment is valid (check Network tab under /sse/index)
- The `outerHTML` assignment might be failing

### Issue 4: Very slow or stuck progress
- The rebuild might be slow
- Watch the progress percentage - it should increment
- Check server logs for any rebuild errors

## Expected Behavior Timeline

```
t=0s:     Click "Rebuild Index"
          → UI might show confirmation
          → EventSource connection should be established

t=0.5s:   First progress event arrives
          → Console shows: SSE Message received - lastEvent: indexProgress
          → Progress bar appears with 0-5%

t=2s:     Progress updates several times
          → Console shows multiple SSE messages
          → Progress bar: 10%, 20%, 30%, etc.

t=30-60s: Rebuild nears completion
          → Progress bar: 90%, 95%, 99%

t=60s:    Rebuild complete
          → Last progress event (100%)
          → IndexStatusUpdated event arrives
          → Summary statistics update
          → Console shows: SSE Message received - lastEvent: indexSummary
          → Total Files, Indexed, Pending counts change automatically
```

## Verification Checklist

- [ ] Page loads without errors
- [ ] "HTMX SSE extension loaded" appears in console
- [ ] "Creating EventSource" appears in console
- [ ] Click Rebuild Index without errors
- [ ] Progress events appear in console during rebuild
- [ ] Progress bar appears on page
- [ ] Progress percentage increases during rebuild
- [ ] Summary statistics update automatically when complete
- [ ] No "Cannot read properties of null" errors
- [ ] No JavaScript errors about dispatchEvent
- [ ] Total Files count changes after rebuild
- [ ] Indexed count changes after rebuild
- [ ] Page does NOT require manual refresh

## Next Steps

If all checks pass, the SSE real-time update system is working correctly!

If something fails:
1. Note which step failed
2. Check the console output carefully
3. Screenshot the console error
4. Check the Network tab to see if events are being sent to the browser
5. Report the specific console message or behavior
