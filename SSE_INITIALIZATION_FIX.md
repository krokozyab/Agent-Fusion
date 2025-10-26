# SSE Initialization Fix - DASH-042

## Problem
SSE (Server-Sent Events) extension was not initializing properly. The JavaScript console showed "No data-sse-url found on body element", preventing the EventSource connection from being established.

## Root Cause
The `htmx-sse.min.js` script runs in the `<head>` section before the `<body>` element is parsed by the browser. When the script tried to read the `data-sse-url` attribute from the body element, it was still `null` because the DOM hadn't finished parsing.

### Timeline of Execution
```
1. Browser parses <head>
2. Script htmx-sse.min.js loads and executes immediately
3. Code tries: var body = document.body  ← Returns null
4. Code tries: body.getAttribute('data-sse-url')  ← ERROR: Cannot read from null
5. EventSource never gets created
6. Browser continues parsing <body>
7. Body element with data-sse-url becomes available
8. But by then, the script already failed
```

## Solution
Added a retry loop that waits for the `document.body` element to be available before attempting to read the `data-sse-url` attribute and create the EventSource.

### Code Changes

**File: `src/main/resources/static/js/htmx-sse.min.js`**

```javascript
// OLD CODE (lines 115-189)
var body = document.body || document.documentElement;
var sseUrl = body.getAttribute('data-sse-url');
if (sseUrl) { /* ... create EventSource ... */ }
else { console.log("No data-sse-url found on body element"); }

// NEW CODE
function initEventSource() {
  var body = document.body;
  if (!body) {
    console.debug("Body element not ready yet, retrying in 100ms...");
    setTimeout(initEventSource, 100);
    return;
  }

  var sseUrl = body.getAttribute('data-sse-url');
  if (!sseUrl) {
    console.log("No data-sse-url found on body element");
    return;
  }

  // ... create EventSource and attach listeners ...
}

// Call initEventSource() from within initSSEExtension()
initEventSource();
```

### How It Works Now

```
1. Browser parses <head>
2. Script htmx-sse.min.js loads and runs initSSEExtension()
3. initSSEExtension() calls initEventSource()
4. initEventSource() checks if document.body exists
5. If not ready, schedules retry in 100ms and returns
6. Browser continues parsing <body>
7. Timer fires and initEventSource() is called again
8. Now document.body IS available
9. EventSource is created successfully
10. SSE connection established
```

## Key Pattern
This mirrors the HTMX initialization pattern - both dependent libraries need to **wait for their prerequisites** before initialization:

- **HTMX Case**: SSE extension needs to wait for HTMX to be available
- **SSE Case**: EventSource needs to wait for body element to be parsed

Both use similar retry logic:
```javascript
function init() {
  if (!prerequisite) {
    setTimeout(init, 100);
    return;
  }
  // Proceed with initialization
}
init();
```

## Commits
- **DASH-042 (cf12f12)**: Fix SSE EventSource initialization timing - Wait for body element
- **DASH-042 (5c8f042)**: Improve EventSource error logging with readyState details

## Testing
To verify the fix works:

1. Navigate to `http://localhost:8081/index`
2. Hard-refresh the page (Cmd+Shift+R or Ctrl+Shift+R)
3. Open Developer Tools (F12) → Console tab
4. Look for these messages in order:
   ```
   ✅ "HTMX SSE extension loaded and registered"
   ✅ "SSE extension initialized by HTMX"
   ✅ "Body element not ready yet, retrying in 100ms..." (may see this)
   ✅ "Found SSE URL: /sse/index"
   ✅ "Created EventSource for: /sse/index"
   ```
5. Click "Rebuild Index" button
6. Should see progress updates without manual page refresh
7. Should see swap messages like "Swap completed successfully"

## Files Modified
- `src/main/resources/static/js/htmx-sse.min.js` - Added retry logic for body element availability

## Notes
- The SSE endpoint `/sse/index` is properly registered and works correctly
- The `data-sse-url="/sse/index"` attribute is correctly set on the body element
- The retry loop uses 100ms intervals, matching HTMX's retry strategy
- EventSource errors (like the one mentioned) are logged with full readyState information for debugging

## Related
- DASH-037: Script loading order fix for HTMX extension
- DASH-038: Remove HTMX's built-in SSE and implement custom handler
- DASH-040: Identify root cause as button swap conflict
- DASH-041: Return 204 No Content and add hx-boost="false"
