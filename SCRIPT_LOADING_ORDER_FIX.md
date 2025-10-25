# Script Loading Order Fix - DASH-037

## Problem Identified

When clicking "Rebuild Index", console error appeared:
```
Uncaught TypeError: Cannot read properties of null (reading 'dispatchEvent')
    at Object.ce [as trigger] (htmx.min.js:1:26755)
```

This was the SAME TYPE of issue as the Mermaid diagram rendering problem - a script loading order issue.

## Root Cause

The SSE extension script (`htmx-sse.min.js`) was being loaded AFTER HTMX, but the page's `hx-ext="sse"` attribute on the body tag was being processed by HTMX before our extension had a chance to register itself.

**Timeline of the problem:**
1. Browser loads `htmx.min.js` ✓
2. Browser loads `htmx-sse.min.js` ✓ (but extension hasn't fully initialized yet)
3. Browser loads `sse-handler.js` (conflicting/redundant script) ✓
4. HTML body with `hx-ext="sse"` is parsed by HTMX
5. HTMX looks for "sse" extension - **NOT FOUND YET** ✗
6. When SSE events arrive, HTMX tries to process them without our handler
7. Error occurs when HTMX tries to call methods on null targets

## Solution Implemented

### 1. Script Loading Order (IndexStatusPage.kt)
**Before:**
```kotlin
script(src = "/static/js/htmx.min.js") {}
script(src = "/static/js/htmx-sse.min.js") {}
script(src = "/static/js/sse-handler.js") {}
script(src = "/static/js/app.js") {}
```

**After:**
```kotlin
script(src = "/static/js/htmx.min.js") {}
// Load our custom SSE extension IMMEDIATELY after HTMX
// Must be before body hx-ext="sse" is processed
script(src = "/static/js/htmx-sse.min.js") {}
// sse-handler.js is redundant now - our htmx-sse.min.js handles everything
script(src = "/static/js/app.js") {}
```

**Key changes:**
- Removed `sse-handler.js` (it was conflicting with our proper extension)
- Ensured `htmx-sse.min.js` loads right after `htmx.min.js`
- Added comments explaining the critical ordering

### 2. Extension Initialization (htmx-sse.min.js)
**Added retry logic to wait for HTMX:**
```javascript
function initSSEExtension() {
  if (!window.htmx) {
    console.debug("HTMX not ready yet, retrying in 100ms...");
    setTimeout(initSSEExtension, 100);
    return;
  }
  // ... rest of initialization
}
initSSEExtension();
```

**Proper HTMX extension registration:**
```javascript
htmx.defineExtension('sse', {
  init: function(api) {
    console.log("SSE extension initialized by HTMX");
  }
});
```

**Early event interception:**
```javascript
document.addEventListener("htmx:sseBeforeMessage", function(evt) {
  console.log("htmx:sseBeforeMessage - lastEvent:", evt.detail.lastEvent);
  evt.preventDefault();
  evt.stopImmediatePropagation();
}, true); // ← Use CAPTURE phase for early interception
```

## Files Changed

1. **src/main/kotlin/com/orchestrator/web/pages/IndexStatusPage.kt**
   - Removed sse-handler.js from script loading
   - Reordered scripts with comments explaining the importance

2. **src/main/resources/static/js/htmx-sse.min.js**
   - Added `initSSEExtension()` function with retry logic
   - Added proper `htmx.defineExtension()` call
   - Improved event listener with capture phase
   - Better error handling and logging

## Why This Mirrors the Mermaid Fix

Just like the Mermaid diagram loading issue, this problem was about execution order:

**Mermaid Problem:** Mermaid library needed to be loaded and initialized before the page tried to render diagrams.

**SSE Problem:** Our SSE extension needed to be registered with HTMX before HTMX processed the `hx-ext="sse"` attribute on the body tag.

Both solutions involve ensuring dependent scripts load and initialize in the correct order.

## How It Works Now

1. **Script Load Order**
   - `htmx.min.js` loads
   - `htmx-sse.min.js` loads and starts initialization
   - `htmx-sse.min.js` waits for HTMX to be available (retry loop)
   - Extension registers with HTMX via `htmx.defineExtension()`
   - Event listeners attached to document
   - `app.js` loads
   - Body HTML parsed with `hx-ext="sse"`
   - HTMX finds "sse" extension (now registered!)
   - Page works correctly

2. **When SSE Events Arrive**
   - HTMX fires `htmx:sseBeforeMessage` event
   - Our listener intercepts with capture phase (fires first)
   - We call `preventDefault()` and `stopImmediatePropagation()`
   - HTMX still fires `htmx:sseMessage` (normal event flow)
   - Our `htmx:sseMessage` listener processes the event
   - DOM updates correctly via `outerHTML` replacement

## Testing

Navigate to http://localhost:8081/index and click "Rebuild Index". You should now see:

**In Console:**
```
HTMX SSE extension loaded and registered
SSE extension initialized by HTMX
Creating EventSource for: /sse/index
htmx:sseBeforeMessage - lastEvent: indexProgress
SSE Message received - lastEvent: indexProgress
Found swap target for event: indexProgress
Performing outerHTML swap
Swap completed successfully
[... more progress events ...]
htmx:sseBeforeMessage - lastEvent: indexSummary
SSE Message received - lastEvent: indexSummary
Found swap target for event: indexSummary
Performing outerHTML swap
Swap completed successfully
```

**On Page:**
- Progress bar appears (0% → 100%)
- Summary statistics update automatically
- NO manual refresh needed
- NO console errors

## Commit

**Commit Hash:** f2bbb29
**Branch:** dev
**Message:** DASH-037: Fix SSE Extension Script Loading Order

## Key Insight

Script execution order is critical when dealing with libraries that need to register themselves with other systems. Always ensure:
1. Base library loads first (HTMX)
2. Extension initializes and registers itself
3. Code that uses the extension loads after

This applies to any plugin/extension system where one script depends on another being fully initialized.

