# Context Explorer Troubleshooting

## Issue: "toggleFilters is not defined" Error

### Cause
The JavaScript functions are embedded inline in the HTML `<head>` section, but the browser is serving a cached version of the page.

### Solution

**Option 1: Restart Server + Hard Refresh (Recommended)**
1. Stop the running server (Ctrl+C)
2. Rebuild: `./gradlew build`
3. Start server: `java -jar build/libs/orchestrator-*-all.jar`
4. Hard refresh browser: `Ctrl+Shift+R` (Windows/Linux) or `Cmd+Shift+R` (Mac)

**Option 2: Clear Browser Cache**
1. Open DevTools (F12)
2. Right-click refresh button → "Empty Cache and Hard Reload"
3. Or: Settings → Clear browsing data → Cached images and files

**Option 3: Incognito/Private Window**
1. Open new incognito/private window
2. Navigate to `http://localhost:8080/explorer`
3. Functions will work (no cache)

### Verification

Open browser console (F12) and type:
```javascript
typeof toggleFilters
```

Should return: `"function"`

If it returns `"undefined"`, the page is still cached.

### Technical Details

The JavaScript functions are now embedded inline in the `<head>` section:
- `toggleFilters()` - Toggle filter panel visibility
- `updateTokensDisplay()` - Update token slider display
- `resetFilters()` - Reset all filters
- `saveFilters()` - Save to localStorage
- `copyToClipboard()` - Copy code snippets

These functions are minified and embedded directly in the HTML to ensure they're always available before any `onclick` handlers execute.

### Cache Busting

The CSS now includes a version parameter: `?v=20250115`

This forces browsers to reload the page when the version changes.

## Issue: Explorer scripts not running on first load (htmx boost)

### Symptoms
- Explorer page loads but JS logs (e.g., `[Explorer] script loaded`) never appear until you manually reload.
- “Open” buttons only work after a full page reload; before that you only see `Click event ...` from `modal.js`.
- The modal shows a gray overlay or does nothing on first navigation.

### Cause
- Global `hx-boost` on the navigation caused cross-page HTMX swaps that brought over HTML without reloading page-scoped scripts (Explorer JS). The Explorer link had `disableBoost`, but the parent nav still applied boost, so scripts were skipped on first visit.

### Fix
1) Drop the global nav `hx-boost` and set `hx-boost` per-link only when allowed; explicitly set `hx-boost=\"false\"` when a link disables boost. (See `Navigation.kt`.)
2) Keep `enableHtmxBoost = false` for Context Explorer (and any other page that requires its own JS bundle).
3) Hard refresh after deploying the change.

### Verification
- Navigate to `/explorer` from any page (no manual full reload). Check console:
  - `[Explorer] script loaded` should appear.
  - Clicking “Open” should log `[Explorer] openFile clicked` and render the modal immediately.
