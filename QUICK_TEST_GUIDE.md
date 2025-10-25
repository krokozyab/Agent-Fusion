# Quick Test Guide - SSE Real-Time Updates

## In 30 Seconds

1. Go to: http://localhost:8081/index
2. Press F12 → Console
3. Press Cmd/Ctrl+Shift+R (hard refresh)
4. Click "Rebuild Index"
5. Watch: Progress bar updates + Summary stats change automatically (no refresh needed)
6. Check console: Should see `HTMX SSE extension loaded` and multiple success messages

---

## Expected Console Output

```
✅ HTMX SSE extension loaded
✅ Creating EventSource for: /sse/index
✅ htmx:sseBeforeMessage - lastEvent: indexProgress
✅ SSE Message received - lastEvent: indexProgress
✅ Found swap target for event: indexProgress
✅ Performing outerHTML swap
✅ Swap completed successfully

[More updates...]

✅ htmx:sseBeforeMessage - lastEvent: indexSummary
✅ SSE Message received - lastEvent: indexSummary
✅ Found swap target for event: indexSummary
✅ Performing outerHTML swap
✅ Swap completed successfully
```

**No error messages!**

---

## What Should Happen

| Before | During Rebuild | After Rebuild |
|--------|---|---|
| Stats: 400/200/200/0 | Progress: 0%→100% | Stats: 510/260/250/0 ✨ |
| Progress: Hidden | Stats: Same | Progress: Hidden |
| | Console: Logs | Console: Success msgs |

---

## If Something's Wrong

| Problem | Fix |
|---------|-----|
| No console messages | Hard refresh: Cmd/Ctrl+Shift+R |
| "Cannot read properties of null" error | Hard refresh (old code cached) |
| Stats don't update | Check Network tab for `/sse/index` connection |
| Progress works but stats don't | Wait longer or check server logs |

---

## Key Files Changed

- `src/main/kotlin/com/orchestrator/web/routes/SSERoutes.kt` - Backend event routing
- `src/main/resources/static/js/htmx-sse.min.js` - Frontend SSE handler

**Latest commit**: 21e7b46 (Fix SSE event handling - Prevent HTMX from processing)

---

## Success =

✅ Page loads
✅ Console shows SSE extension loaded
✅ Progress bar updates in real-time
✅ Summary stats update automatically when done
✅ No manual page refresh needed
✅ No console errors

