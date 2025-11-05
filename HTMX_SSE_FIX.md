# HTMX SSE htmlFragment Swap Fix

## Problem
Summary statistics (Total Files, Indexed, Pending, Failed) on the Index Status dashboard were not updating in real-time when index rebuild completed. They required manual page refresh.

### Root Cause
The HTMX SSE extension was not properly parsing the JSON payload containing the `htmlFragment` field and swapping it into the target element.

## Event Flow
```
Backend:
1. IndexOperationsService.triggerRebuild() completes rebuild
2. publishSummary() creates IndexStatusUpdatedEvent
3. EventBusSubscriber.handleIndexStatusUpdate() listens for this event
4. Renders updated summary using fragmentGenerator.indexSummary()
5. Creates SSE event with htmlFragment containing the new summary HTML
6. Broadcasts IndexStatusUpdatedEvent via SSE with:
   - event type: "message"
   - data: JSON string {"event":"indexSummary",...}
   - htmlFragment: HTML string of new summary with sse-swap="indexSummary"

Frontend:
1. Browser receives SSE event with type="message"
2. HTMX SSE extension receives htmx:sseMessage event
3. Extension MUST:
   - Parse JSON payload to extract htmlFragment
   - Find element with matching sse-swap attribute
   - Replace that element's outerHTML with the new fragment
```

## Solution
Updated `htmx-sse.min.js` to properly handle htmlFragment swapping.

### What the Fix Does

#### Before (Broken):
```javascript
// Tried to use htmx.ajax() incorrectly
// Didn't properly extract htmlFragment from JSON
// Resulted in "Cannot read properties of null" errors
```

#### After (Fixed):
```javascript
document.addEventListener("htmx:sseMessage", function(event) {
    if (event.detail && event.detail.data) {
        try {
            // Parse the outer JSON containing htmlFragment
            var payload = typeof event.detail.data === "string"
                ? JSON.parse(event.detail.data)
                : event.detail.data;

            // Check if htmlFragment exists
            if (payload.htmlFragment) {
                // Create temporary container to parse HTML safely
                var temp = document.createElement("div");
                temp.innerHTML = payload.htmlFragment;

                // Get the actual element (skip wrapper if needed)
                var element = temp.firstElementChild;

                if (element) {
                    // Extract the sse-swap identifier
                    var swapId = element.getAttribute("sse-swap");

                    if (swapId) {
                        // Find the target element on the page
                        var target = document.querySelector('[sse-swap="' + swapId + '"]');

                        if (target) {
                            // Replace the element in the DOM
                            target.outerHTML = element.outerHTML;

                            // Dispatch custom event for logging/debugging
                            var swapEvent = new CustomEvent("htmx:sseSwap", {
                                detail: {
                                    target: target,
                                    fragment: element,
                                    eventName: swapId
                                },
                                bubbles: true
                            });
                            document.dispatchEvent(swapEvent);
                        }
                    }
                }
            }
        } catch (error) {
            console.debug("Error parsing SSE htmlFragment:", error);
        }
    }
}, false);
```

## Key Improvements

1. **Proper JSON Parsing**: Correctly extracts `htmlFragment` from the SSE event payload
2. **Safe HTML Parsing**: Creates a temporary div to parse HTML before DOM manipulation
3. **Attribute Matching**: Uses `sse-swap` attribute to identify both source (in fragment) and target (on page)
4. **Direct DOM Replacement**: Uses `outerHTML` assignment for efficient swap
5. **Error Handling**: Catches and logs parse errors without breaking the page
6. **Custom Events**: Dispatches `htmx:sseSwap` event for monitoring/debugging

## Files Changed

- **htmx-sse.min.js**: Updated event listener to properly handle JSON parsing and htmlFragment swapping

## Expected Behavior After Fix

1. Click "Rebuild Index" button
2. Progress bar appears and updates in real-time
3. When rebuild completes:
   - Summary statistics update **automatically** without page refresh
   - Total Files, Indexed, Pending, Failed counts change
   - No console errors
   - Custom event `htmx:sseSwap` dispatched for each update

## Testing the Fix

### Manual Test Steps:
1. Start application: `./gradlew run`
2. Navigate to `/index` page
3. Open DevTools console
4. Click "Rebuild Index" button
5. Verify:
   - Progress bar appears (0% → 100%)
   - Summary statistics update in real-time
   - No console errors (except any pre-existing ones)
   - Monitor for `htmx:sseSwap` events in console:
     ```javascript
     document.addEventListener('htmx:sseSwap', (e) => {
         console.log('SSE Swap:', e.detail.eventName);
     });
     ```

### Test Output Expected:
```
Console:
  "HTMX SSE extension loaded"
  "Creating EventSource"

While rebuilding:
  "SSE Swap: indexProgress" (multiple times as progress updates)

When rebuild completes:
  "SSE Swap: indexSummary" (summary stats update)

Page UI:
  [✓] Progress bar appears
  [✓] Stats update live: 0→100→200→...→510 Total Files
  [✓] Indexed count updates
  [✓] Pending count updates
  [✓] Failed count shows 0 or updates
  [✓] No manual page refresh needed
```

## Technical Details

### SSE Event Structure
```json
{
  "event": "message",
  "data": "{\"event\":\"indexSummary\",\"timestamp\":\"...\",\"totalFiles\":510,...}",
  "htmlFragment": "<div id=\"index-summary\" sse-swap=\"indexSummary\" ...>...</div>"
}
```

### Matching Algorithm
1. Fragment contains: `<div sse-swap="indexSummary">`
2. Page has: `<div id="index-summary" sse-swap="indexSummary">`
3. Query selector: `[sse-swap="indexSummary"]`
4. Match found → Replace element

### Why This Works
- Server-side: Creates fragment with `sse-swap="identifier"`
- Server sends: JSON payload with htmlFragment field
- Client-side: Parses JSON, extracts htmlFragment, finds matching target, swaps HTML
- Result: Real-time UI updates without page reload

## Verification Checklist

- [x] Code compiles successfully
- [x] No TypeScript/JavaScript syntax errors
- [x] htmx-sse.min.js minified and valid
- [x] Script loaded after htmx.min.js and before other scripts
- [x] Fragment generator adds proper sse-swap attributes
- [x] Page has corresponding container elements with sse-swap attributes
- [x] Error handling catches JSON parse errors
- [ ] Manual testing confirms real-time updates (pending user test)
- [ ] No console errors during operation (pending user test)

## Notes

This fix addresses the core issue where summary statistics required manual page refresh. The HTMX SSE extension now properly:
1. Listens for SSE events
2. Parses JSON payloads with nested htmlFragment
3. Finds target elements by sse-swap attribute
4. Performs DOM replacement

The solution is minimal, focused, and doesn't break existing functionality.
