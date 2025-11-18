# Context Explorer – Detailed Implementation Plan

**Objective**: Embed a query_context console into the web dashboard with rich result cards, plus a "related graph" preview that shows how returned snippets connect via existing links data.

**Status**: Planning complete, ready for development
**Created**: 2025-11-15
**Last Updated**: 2025-11-15

---

## Table of Contents

1. [Overview](#overview)
2. [Task Breakdown by Milestone](#task-breakdown-by-milestone)
3. [Milestone 1 – Query Console UI](#milestone-1--query-console-ui-frontend)
4. [Milestone 2 – Related Graph Preview](#milestone-2--related-graph-preview)
5. [Milestone 3 – Quality/Config Feedback](#milestone-3--qualityconfig-feedback)
6. [Dependencies & Integration](#dependencies--integration)
7. [Estimated Effort & Timeline](#estimated-effort--timeline)

---

## Overview

**Goal**: Deliver a fully functional code search console within the web dashboard that:
- Accepts natural language queries (short keywords, not questions)
- Returns semantic + symbol + full-text results with rich metadata
- Visualizes related code dependencies in an interactive graph
- Provides quality feedback on search results (dominant sources, token usage)
- Saves queries for quick re-runs across sessions

**Key Features**:
- ✅ Multi-filter search UI (paths, languages, kinds, exclude patterns)
- ✅ Result cards with snippet preview + actions (open, copy, view related)
- ✅ Interactive related graph showing imports/calls/references
- ✅ Dominance detection (alert if >50% results from same file)
- ✅ Saved queries sidebar with persistence
- ✅ Query telemetry & performance monitoring
- ✅ Keyboard shortcuts & UX polish

**Scope Exclusions**:
- No search backend changes (uses existing QueryContextTool)
- No full-page graph visualization (side panel only)
- No multi-user saved queries (local storage + optional backend)

---

## Task Breakdown by Milestone

### All Tasks at a Glance

**Milestone 1** (8 tasks, ~2-3 days):
- 1.1 Design console layout
- 1.2 Create page route
- 1.3 Build query input form
- 1.4 Implement result cards
- 1.5 Add result actions
- 1.6 Implement /api/query endpoint
- 1.7 Error/empty state handling
- 1.8 Frontend event handling & pagination

**Milestone 2** (6 tasks, ~2-3 days):
- 2.1 Extend QueryContextTool result with links
- 2.2 Query links table for related chunks
- 2.3 Create related graph API endpoint
- 2.4 Build graph visualization (Vis.js)
- 2.5 Implement side panel UI
- 2.6 Add drill-down to related chunks

**Milestone 3** (4 tasks, ~1-2 days):
- 3.1 Dominance detection & alerts
- 3.2 Saved queries (local storage + backend)
- 3.3 Query telemetry & logging
- 3.4 UX polish (shortcuts, filter memory)

---

## MILESTONE 1 – Query Console UI (Frontend)

### Task 1.1 – Design Console Layout & Components

**Objective**: Create HTML/UI specification for the query console

**Output**:
- Design document: `devdoc/CONTEXT_EXPLORER_DESIGN.md`
- HTML skeleton & component structure

**Deliverables**:

1. **Dashboard Tab Structure**
   - Add new tab: `Context Explorer` (alongside Tasks, Index Status)
   - Tab content sections:
     - Search bar (top)
     - Filter panel (collapsible sidebar)
     - Results grid/list (main area)
     - Status bar (footer)

2. **Search Bar Layout**
   ```
   [🔍 Query Input ________________] [Run] [Clear]
   [≡ Filters] ↓
   ```
   - Single-line query input
   - Run/Clear action buttons
   - Collapsible filter toggle

3. **Filter Panel** (collapsed by default)
   ```
   Paths:           [text area - one per line]
   Languages:       [☐ Kotlin ☐ Java ☐ Python ...]
   Kinds:           [☐ CODE_CLASS ☐ CODE_FUNCTION ...]
   Exclude:         [text area - one per line]
   Max Results (k): [slider: 1-100, default 20]
   Max Tokens:      [slider: 500-20000, default 6000]
   [Reset] [Save as Query]
   ```

4. **Result Card Structure**
   ```
   ┌─────────────────────────────────────────────────┐
   │ src/main/kotlin/auth/JwtValidator.kt:42  [0.85] │
   │ CODE_CLASS                                      │
   │ ─────────────────────────────────────────────── │
   │ class JwtValidator {                            │
   │   fun validate(token: String): Boolean {        │
   │     // Validates JWT token signature           │
   │ ─────────────────────────────────────────────── │
   │ Kotlin | 245 tokens | semantic, symbol          │
   │ [Open] [Copy] [Related]                         │
   └─────────────────────────────────────────────────┘
   ```
   - File path (top-left, clickable → open in editor)
   - Score badge (top-right, 0.0-1.0)
   - Kind tag (CODE_CLASS, CODE_FUNCTION, etc.)
   - Snippet preview (max 3 lines, syntax highlighted if possible)
   - Query terms highlighted in yellow
   - Metadata: language, token count, providers
   - Action buttons: Open, Copy, Related Graph

5. **Status Bar** (footer)
   ```
   ⏱️ 234ms | 45 results | semantic: 30 | symbol: 10 | fulltext: 5
   ```
   - Query execution time
   - Total hits count
   - Provider contribution breakdown

6. **Pagination UI**
   ```
   [Previous] [1] [2] [3] ... [10] [Next]
   OR
   Showing 1-20 of 145 results  [Load More (20)]
   ```

**Dependencies**: None (design-only)

**Files to Create**:
- `devdoc/CONTEXT_EXPLORER_DESIGN.md` (this design spec)

**Acceptance Criteria**:
- [ ] Component hierarchy documented
- [ ] Layout mockups ASCII or reference to Figma/wireframe
- [ ] Result card structure clearly specified
- [ ] Action buttons & interactions listed

---

### Task 1.2 – Create Query Console Page Route in Ktor

**Objective**: Add Ktor route that serves the console page

**Output**:
- New Ktor route handler: `GET /explorer` → returns full HTML page
- Page generator function in QueryConsolePage.kt
- Integrated into dashboard navigation

**Implementation**:

1. **Create** `src/main/kotlin/com/orchestrator/web/pages/QueryConsolePage.kt`:
   ```kotlin
   package com.orchestrator.web.pages

   fun queryConsoleHTML(): String {
     return """
       <!DOCTYPE html>
       <html>
       <head>
         <title>Context Explorer</title>
         <link rel="stylesheet" href="/css/query-console.css">
         <script src="https://unpkg.com/htmx.org@1.9.10"></script>
       </head>
       <body>
         <div class="explorer-container">
           <h1>Context Explorer</h1>
           <div class="search-section">
             <!-- Search bar & filters go here -->
           </div>
           <div id="results">
             <!-- Results loaded via HTMX -->
           </div>
           <div id="graph-panel" class="side-panel" style="display: none;">
             <!-- Graph visualization goes here -->
           </div>
         </div>
         <script src="/js/query-console.js"></script>
       </body>
       </html>
     """.trimIndent()
   }
   ```

2. **Create** `src/main/kotlin/com/orchestrator/web/routes/QueryRoutes.kt`:
   ```kotlin
   package com.orchestrator.web.routes

   import io.ktor.server.routing.*
   import io.ktor.server.response.*
   import io.ktor.http.*

   fun Route.queryRoutes() {
     route("/explorer") {
       get {
         call.respondText(queryConsoleHTML(), ContentType.Text.Html)
       }
     }
   }
   ```

3. **Modify** `src/main/kotlin/com/orchestrator/web/WebServer.kt`:
   - Add import: `import com.orchestrator.web.routes.queryRoutes`
   - Add to routing:
     ```kotlin
     routing {
       queryRoutes()
       // ... other routes
     }
     ```

4. **Modify** `src/main/kotlin/com/orchestrator/web/pages/HomeRoutes.kt` (or equivalent):
   - Add tab link to dashboard:
     ```html
     <a href="/explorer" class="tab">Context Explorer</a>
     <a href="/tasks" class="tab">Tasks</a>
     <a href="/index" class="tab">Index Status</a>
     ```

**Dependencies**: Task 1.1 (design reference)

**Files**:
- New: `web/pages/QueryConsolePage.kt`
- New: `web/routes/QueryRoutes.kt`
- Modify: `web/WebServer.kt`
- Modify: Home page HTML template

**Acceptance Criteria**:
- [ ] Route responds with 200 OK and HTML content-type
- [ ] Page loads without JavaScript errors
- [ ] Dashboard tab "Context Explorer" navigates to `/explorer`
- [ ] Page displays basic structure (header, search section, results div)

---

### Task 1.3 – Build Query Input Form with Filter Controls

**Objective**: Implement interactive filter UI + form state management

**Output**:
- HTMX form elements with client-side validation
- Filter state persistence to localStorage
- Form reset functionality

**Implementation**:

1. **Query Input Section** (in QueryConsolePage.kt):
   ```html
   <div class="search-bar">
     <input type="text" id="query-input" placeholder="Search code...
              (e.g., 'authentication JWT token')"
            class="query-input" required minlength="2"
            hx-trigger="change delay:500ms"
            hx-post="/api/query"
            hx-target="#results">
     <button hx-post="/api/query" hx-target="#results">Run</button>
     <button hx-post="/api/query-reset" hx-target="#query-form">Clear</button>
   </div>
   ```

2. **Filter Panel** (collapsible):
   ```html
   <div class="filter-panel" id="filter-panel" style="display: none;">
     <div class="filter-group">
       <label>Paths</label>
       <textarea id="paths-filter" placeholder="src/main
   src/test" class="filter-input"></textarea>
     </div>

     <div class="filter-group">
       <label>Languages</label>
       <div class="checkboxes">
         <label><input type="checkbox" name="language" value="kotlin"> Kotlin</label>
         <label><input type="checkbox" name="language" value="java"> Java</label>
         <label><input type="checkbox" name="language" value="python"> Python</label>
         <label><input type="checkbox" name="language" value="javascript"> JavaScript</label>
         <label><input type="checkbox" name="language" value="typescript"> TypeScript</label>
         <label><input type="checkbox" name="language" value="markdown"> Markdown</label>
       </div>
     </div>

     <div class="filter-group">
       <label>Kinds</label>
       <div class="checkboxes">
         <label><input type="checkbox" name="kind" value="CODE_CLASS"> Code Class</label>
         <label><input type="checkbox" name="kind" value="CODE_FUNCTION"> Code Function</label>
         <label><input type="checkbox" name="kind" value="CODE_METHOD"> Code Method</label>
         <label><input type="checkbox" name="kind" value="CODE_COMMENT"> Code Comment</label>
       </div>
     </div>

     <div class="filter-group">
       <label>Exclude Patterns</label>
       <textarea id="exclude-filter" placeholder="**/test/**
   **/__pycache__/**" class="filter-input"></textarea>
     </div>

     <div class="filter-group">
       <label>Max Results (k)</label>
       <input type="range" id="k-slider" min="1" max="100" value="20">
       <span id="k-display">20</span>
     </div>

     <div class="filter-group">
       <label>Max Tokens</label>
       <input type="range" id="tokens-slider" min="500" max="20000" value="6000" step="500">
       <span id="tokens-display">6000</span>
     </div>

     <div class="filter-actions">
       <button onclick="resetFilters()">Reset Filters</button>
       <button onclick="saveAsQuery()" hx-get="/api/save-query-dialog" hx-target="body">Save as Query</button>
     </div>
   </form>
   ```

3. **Client-Side Validation** (in JS):
   ```javascript
   // Validate query on input
   document.getElementById('query-input').addEventListener('change', (e) => {
     const query = e.target.value.trim();
     if (query.length < 2) {
       showError('Query must be at least 2 characters');
       return;
     }
     saveToLocalStorage('lastQuery', query);
   });

   // Auto-save filters to localStorage
   function saveFiltersToLocalStorage() {
     const filters = {
       paths: document.getElementById('paths-filter').value,
       languages: Array.from(document.querySelectorAll('input[name="language"]:checked')).map(x => x.value),
       kinds: Array.from(document.querySelectorAll('input[name="kind"]:checked')).map(x => x.value),
       exclude: document.getElementById('exclude-filter').value,
       k: document.getElementById('k-slider').value,
       maxTokens: document.getElementById('tokens-slider').value
     };
     localStorage.setItem('queryFilters', JSON.stringify(filters));
   }

   // Restore filters on page load
   function restoreFiltersFromLocalStorage() {
     const filters = JSON.parse(localStorage.getItem('queryFilters') || '{}');
     if (filters.paths) document.getElementById('paths-filter').value = filters.paths;
     if (filters.languages) {
       filters.languages.forEach(lang => {
         document.querySelector(`input[name="language"][value="${lang}"]`).checked = true;
       });
     }
     // ... restore other filters
   }

   // Slider value display updates
   document.getElementById('k-slider').addEventListener('input', (e) => {
     document.getElementById('k-display').textContent = e.target.value;
     saveFiltersToLocalStorage();
   });

   // Reset all filters
   function resetFilters() {
     document.getElementById('query-input').value = '';
     document.getElementById('paths-filter').value = '';
     document.getElementById('exclude-filter').value = '';
     document.querySelectorAll('input[type="checkbox"]').forEach(cb => cb.checked = false);
     document.getElementById('k-slider').value = 20;
     document.getElementById('tokens-slider').value = 6000;
     localStorage.removeItem('queryFilters');
   }

   // Initialize on page load
   document.addEventListener('DOMContentLoaded', () => {
     restoreFiltersFromLocalStorage();
     // Restore last query if enabled
     const lastQuery = localStorage.getItem('lastQuery');
     if (lastQuery) {
       document.getElementById('query-input').value = lastQuery;
     }
   });
   ```

4. **Validation Messages**:
   - Query < 2 chars: "Query must be at least 2 characters"
   - K > 50: "⚠️ High K value may use more tokens. Consider <20 for faster results."
   - Invalid paths: Warn but allow (backend validates)

5. **Filter Panel Toggle**:
   ```html
   <button id="toggle-filters" onclick="toggleFilterPanel()">≡ Filters</button>
   ```
   ```javascript
   function toggleFilterPanel() {
     const panel = document.getElementById('filter-panel');
     panel.style.display = panel.style.display === 'none' ? 'block' : 'none';
   }
   ```

**Dependencies**: Task 1.2 (page route exists)

**Files**:
- Modify: `web/pages/QueryConsolePage.kt` (add form HTML)
- New: `resources/static/js/query-console.js` (form logic)
- New: `resources/static/css/query-console.css` (styling)

**Acceptance Criteria**:
- [ ] Query input accepts text, min 2 chars validation works
- [ ] All filters render and are interactive
- [ ] Filters persist to localStorage
- [ ] Filters restore on page reload
- [ ] Reset button clears all fields
- [ ] Slider updates display values in real-time

---

### Task 1.4 – Implement Result Cards Component (HTML fragments)

**Objective**: HTMX-friendly result card HTML generator

**Output**:
- Kotlin function generating paginated result card HTML
- Result card styling in CSS
- Snippet preview with query term highlighting

**Implementation**:

1. **Create** `src/main/kotlin/com/orchestrator/web/pages/ResultCardPage.kt`:
   ```kotlin
   package com.orchestrator.web.pages

   data class QueryContextResult(
     val chunkId: Long,
     val filePath: String,
     val score: Double,
     val kind: String,
     val text: String,
     val language: String,
     val tokens: Int,
     val providers: List<String>,
     val lineStart: Int,
     val lineEnd: Int
   )

   fun resultCardsHTML(
     hits: List<QueryContextResult>,
     query: String,
     page: Int = 0,
     hasMore: Boolean = false
   ): String {
     if (hits.isEmpty()) {
       return "" // Will be handled by empty state in Task 1.7
     }

     val snippets = hits.map { hit ->
       val highlighted = highlightQueryTerms(hit.text, query)
       """
         <div class="result-card" data-chunk-id="${hit.chunkId}">
           <div class="result-header">
             <span class="path" title="${hit.filePath}">${hit.filePath}</span>
             <span class="score">${String.format("%.2f", hit.score)}</span>
             <span class="kind">${hit.kind}</span>
           </div>
           <div class="snippet">
             <pre><code>$highlighted</code></pre>
           </div>
           <div class="metadata">
             <span class="language">${hit.language}</span>
             <span class="tokens">${hit.tokens} tokens</span>
             <span class="providers">${hit.providers.joinToString(", ")}</span>
           </div>
           <div class="actions">
             <button hx-post="/api/actions/open-editor"
                     hx-vals='{"chunkId": ${hit.chunkId}, "filePath": "${hit.filePath}", "lineStart": ${hit.lineStart}, "lineEnd": ${hit.lineEnd}}'
                     class="btn-action">
               📝 Open
             </button>
             <button hx-post="/api/actions/copy-snippet"
                     hx-vals='{"chunkId": ${hit.chunkId}, "snippetText": """${hit.text.replace("\"", "\\\"").replace("\n", "\\n")}"""}'
                     class="btn-action"
                     hx-on::response="showToast('Copied to clipboard')">
               📋 Copy
             </button>
             <button hx-get="/api/graph/${hit.chunkId}"
                     hx-target="#graph-panel"
                     hx-swap="innerHTML"
                     class="btn-action">
               🔗 Related
             </button>
           </div>
         </div>
       """.trimIndent()
     }.joinToString("\n")

     val pagination = if (hasMore) {
       """
         <div class="load-more-section">
           <button hx-post="/api/query"
                   hx-vals='{"offset": ${(page + 1) * hits.size}}'
                   hx-target="#results"
                   hx-swap="appendChild"
                   class="btn-load-more">
             Load More Results
           </button>
         </div>
       """.trimIndent()
     } else {
       ""
     }

     return """
       <div id="results-list">
         $snippets
       </div>
       $pagination
     """.trimIndent()
   }

   fun highlightQueryTerms(text: String, query: String): String {
     val terms = query.split("\\s+".toRegex()).filter { it.isNotEmpty() }
     var result = text
       .replace("<", "&lt;")
       .replace(">", "&gt;")

     terms.forEach { term ->
       result = result.replace(
         term,
         "<mark class=\"highlight\">$term</mark>",
         ignoreCase = true
       )
     }
     return result
   }
   ```

2. **Styling** (`resources/static/css/query-console.css`):
   ```css
   .result-card {
     border: 1px solid #ddd;
     border-radius: 6px;
     padding: 16px;
     margin: 12px 0;
     background: #fff;
     transition: box-shadow 0.2s;
   }

   .result-card:hover {
     box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
   }

   .result-header {
     display: flex;
     justify-content: space-between;
     align-items: center;
     margin-bottom: 8px;
     gap: 12px;
   }

   .path {
     font-weight: 600;
     color: #0066cc;
     cursor: pointer;
     text-decoration: underline;
     flex: 1;
     word-break: break-all;
   }

   .path:hover {
     color: #0052a3;
   }

   .score {
     background: #e8f4f8;
     padding: 2px 8px;
     border-radius: 3px;
     font-size: 0.85rem;
     font-weight: 600;
   }

   .kind {
     background: #f0f0f0;
     padding: 2px 8px;
     border-radius: 3px;
     font-size: 0.75rem;
     text-transform: uppercase;
   }

   .snippet {
     background: #f5f5f5;
     border-left: 3px solid #0066cc;
     padding: 12px;
     margin: 12px 0;
     overflow-x: auto;
     max-height: 150px;
     overflow-y: auto;
   }

   .snippet pre {
     margin: 0;
     font-family: 'Courier New', monospace;
     font-size: 0.9rem;
     line-height: 1.4;
   }

   .snippet code mark.highlight {
     background-color: #ffeb3b;
     padding: 1px 2px;
     border-radius: 2px;
   }

   .metadata {
     display: flex;
     gap: 12px;
     font-size: 0.85rem;
     color: #666;
     margin: 8px 0;
     flex-wrap: wrap;
   }

   .metadata span {
     display: inline-block;
   }

   .metadata .language {
     background: #e6f2ff;
     padding: 2px 6px;
     border-radius: 3px;
   }

   .metadata .providers {
     color: #888;
     font-style: italic;
   }

   .actions {
     display: flex;
     gap: 8px;
     margin-top: 12px;
   }

   .btn-action {
     padding: 6px 12px;
     border: 1px solid #ddd;
     background: #f9f9f9;
     border-radius: 4px;
     cursor: pointer;
     font-size: 0.85rem;
     transition: all 0.2s;
   }

   .btn-action:hover {
     background: #e8e8e8;
     border-color: #0066cc;
   }

   .load-more-section {
     text-align: center;
     margin: 24px 0;
   }

   .btn-load-more {
     padding: 10px 20px;
     background: #0066cc;
     color: white;
     border: none;
     border-radius: 4px;
     cursor: pointer;
     font-size: 0.9rem;
   }

   .btn-load-more:hover {
     background: #0052a3;
   }

   .htmx-request .htmx-indicator {
     display: inline-block;
   }

   .htmx-request.htmx-swapping .btn-action {
     opacity: 0.6;
   }
   ```

3. **Integration** (in QueryConsolePage.kt):
   ```kotlin
   // In the results div, use HTMX to replace with result cards
   """
   <div id="results" class="results-section"></div>
   """
   ```

**Dependencies**: Task 1.3 (filters exist)

**Files**:
- New: `web/pages/ResultCardPage.kt`
- New: `resources/static/css/query-console.css`
- Modify: `web/pages/QueryConsolePage.kt` (reference ResultCardPage)

**Acceptance Criteria**:
- [ ] Result cards render with all fields (file, score, kind, snippet)
- [ ] Query terms highlighted in yellow
- [ ] Action buttons present and properly formatted
- [ ] Pagination button appears when hasMore=true
- [ ] Cards are responsive and readable
- [ ] Hover effects work properly

---

### Task 1.5 – Add Result Card Actions (open/copy/etc)

**Objective**: Implement card action endpoints

**Output**:
- POST `/api/actions/open-editor` endpoint
- POST `/api/actions/copy-snippet` endpoint
- Optional: POST `/api/actions/view-context` endpoint

**Implementation**:

1. **Create** `src/main/kotlin/com/orchestrator/web/services/ResultActionsService.kt`:
   ```kotlin
   package com.orchestrator.web.services

   class ResultActionsService {

     fun generateVSCodeUrl(filePath: String, lineStart: Int, lineEnd: Int): String {
       // VS Code URL scheme: vscode://file/absolute/path:line:column
       val line = lineStart.coerceAtLeast(1)
       val column = 1
       return "vscode://file/${filePath}:${line}:${column}"
     }

     fun generateGithubUrl(
       filePath: String,
       lineStart: Int,
       lineEnd: Int,
       branch: String = "main"
     ): String {
       // GitHub URL: https://github.com/owner/repo/blob/main/path/file#L42-L50
       // Requires: projectRoot detection (git remote origin)
       return "https://github.com/owner/repo/blob/${branch}/${filePath}#L${lineStart}-L${lineEnd}"
     }

     fun escapeSnippet(text: String): String {
       return text
         .replace("\\", "\\\\")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "\\r")
     }
   }
   ```

2. **Add endpoints** to `web/routes/QueryRoutes.kt`:
   ```kotlin
   fun Route.resultActionRoutes() {
     route("/api/actions") {

       post("/open-editor") {
         val chunkId = call.request.queryParameters["chunkId"]?.toLongOrNull()
         val filePath = call.request.queryParameters["filePath"]
         val lineStart = call.request.queryParameters["lineStart"]?.toIntOrNull() ?: 1

         if (filePath.isNullOrBlank()) {
           call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing filePath"))
           return@post
         }

         val service = ResultActionsService()
         val vscodeUrl = service.generateVSCodeUrl(filePath, lineStart, lineStart)

         // Return HTML that triggers navigation
         val html = """
           <script>
             window.location.href = '$vscodeUrl';
             setTimeout(() => {
               // Fallback message if VS Code doesn't open
               alert('Opening in VS Code... If it doesn\\'t open, ensure VS Code is installed and the "code" command is in PATH.');
             }, 1000);
           </script>
         """.trimIndent()

         call.respondText(html, ContentType.Text.Html)
       }

       post("/copy-snippet") {
         val snippetText = call.request.queryParameters["snippetText"]

         if (snippetText.isNullOrBlank()) {
           call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing snippetText"))
           return@post
         }

         // Return JSON response for client-side clipboard handling
         call.respondJson(mapOf(
           "status" to "copied",
           "text" to snippetText,
           "message" to "Copied to clipboard!"
         ))
       }

       post("/view-context") {
         val chunkId = call.request.queryParameters["chunkId"]?.toLongOrNull()
         val filePath = call.request.queryParameters["filePath"]

         if (chunkId == null || filePath.isNullOrBlank()) {
           call.respond(HttpStatusCode.BadRequest)
           return@post
         }

         // Fetch full file content from storage or filesystem
         try {
           val fileContent = java.io.File(filePath).readText()
           val lines = fileContent.split("\n")

           val html = """
             <div class="file-viewer">
               <div class="file-header">$filePath</div>
               <pre><code>${lines.joinToString("\n") { (i, line) ->
                 // Line numbers...
               }}</code></pre>
             </div>
           """.trimIndent()

           call.respondText(html, ContentType.Text.Html)
         } catch (e: Exception) {
           call.respond(HttpStatusCode.NotFound, mapOf("error" to "File not found"))
         }
       }
     }
   }
   ```

3. **Client-side clipboard handling** (in query-console.js):
   ```javascript
   // Override default HTMX response handling for copy
   document.addEventListener('htmx:afterSwap', function(event) {
     if (event.detail.xhr.responseURL.includes('/api/actions/copy-snippet')) {
       const response = JSON.parse(event.detail.xhr.response);

       // Copy to clipboard
       navigator.clipboard.writeText(response.text).then(() => {
         showToast('✓ Copied to clipboard');
       }).catch(() => {
         // Fallback for older browsers
         const textarea = document.createElement('textarea');
         textarea.value = response.text;
         document.body.appendChild(textarea);
         textarea.select();
         document.execCommand('copy');
         document.body.removeChild(textarea);
         showToast('✓ Copied to clipboard');
       });
     }
   });

   function showToast(message) {
     const toast = document.createElement('div');
     toast.className = 'toast';
     toast.textContent = message;
     document.body.appendChild(toast);

     setTimeout(() => {
       toast.classList.add('show');
     }, 10);

     setTimeout(() => {
       toast.classList.remove('show');
       setTimeout(() => document.body.removeChild(toast), 300);
     }, 2000);
   }
   ```

4. **Toast styling** (add to query-console.css):
   ```css
   .toast {
     position: fixed;
     bottom: 20px;
     right: 20px;
     background: #333;
     color: white;
     padding: 12px 20px;
     border-radius: 4px;
     opacity: 0;
     transition: opacity 0.3s;
     z-index: 1000;
   }

   .toast.show {
     opacity: 1;
   }
   ```

**Dependencies**: Task 1.4 (result cards)

**Files**:
- New: `web/services/ResultActionsService.kt`
- Modify: `web/routes/QueryRoutes.kt`
- Modify: `resources/static/js/query-console.js` (add clipboard handling)
- Modify: `resources/static/css/query-console.css` (add toast styles)

**Acceptance Criteria**:
- [ ] "Open in Editor" button generates VS Code URL and opens
- [ ] "Copy Snippet" button copies text to clipboard
- [ ] Toast message appears on copy
- [ ] Error handling for missing parameters
- [ ] Works cross-browser (clipboard API with fallback)

---

### Task 1.6 – Implement /api/query Endpoint (POST handler)

**Objective**: API endpoint that calls QueryContextTool and returns JSON results

**Output**:
- POST `/api/query` endpoint
- QueryService wrapper around QueryContextTool
- Result transformation & pagination

**Implementation**:

1. **Create** `src/main/kotlin/com/orchestrator/web/services/QueryService.kt`:
   ```kotlin
   package com.orchestrator.web.services

   import com.orchestrator.mcp.tools.QueryContextTool
   import com.orchestrator.context.ContextModule
   import kotlinx.serialization.Serializable

   @Serializable
   data class QueryRequest(
     val query: String,
     val k: Int = 20,
     val maxTokens: Int = 6000,
     val paths: List<String> = emptyList(),
     val languages: List<String> = emptyList(),
     val kinds: List<String> = emptyList(),
     val excludePatterns: List<String> = emptyList(),
     val offset: Int = 0
   )

   @Serializable
   data class QueryHit(
     val chunkId: Long,
     val filePath: String,
     val score: Double,
     val kind: String,
     val text: String,
     val language: String,
     val tokens: Int,
     val providers: List<String>,
     val lineStart: Int = 0,
     val lineEnd: Int = 0
   )

   @Serializable
   data class QueryResponse(
     val hits: List<QueryHit>,
     val pagination: PaginationInfo,
     val metadata: QueryMetadata,
     val error: String? = null
   )

   @Serializable
   data class PaginationInfo(
     val offset: Int,
     val returned: Int,
     val totalHits: Int,
     val hasMore: Boolean
   )

   @Serializable
   data class QueryMetadata(
     val queryTime: Long,
     val providers: Map<String, Int>  // {semantic: 15, symbol: 3}
   )

   class QueryService(
     private val contextModule: ContextModule
   ) {

     fun executeQuery(request: QueryRequest): Result<QueryResponse> = runCatching {
       // Validate input
       if (request.query.trim().length < 2) {
         throw IllegalArgumentException("Query must be at least 2 characters")
       }

       val startTime = System.currentTimeMillis()

       // Call QueryContextTool
       val tool = QueryContextTool(contextModule)
       val params = mapOf(
         "query" to request.query,
         "k" to request.k.toString(),
         "maxTokens" to request.maxTokens.toString(),
         "paths" to if (request.paths.isNotEmpty()) request.paths.toTypedArray() else null,
         "languages" to if (request.languages.isNotEmpty()) request.languages.toTypedArray() else null,
         "kinds" to if (request.kinds.isNotEmpty()) request.kinds.toTypedArray() else null,
         "excludePatterns" to if (request.excludePatterns.isNotEmpty()) request.excludePatterns.toTypedArray() else null
       )

       val result = tool.execute(params)

       // Parse result hits
       val hits = result.hits
         .drop(request.offset)
         .take(request.k)
         .mapIndexed { index, hit ->
           QueryHit(
             chunkId = hit.chunkId,
             filePath = hit.filePath,
             score = hit.score,
             kind = hit.kind,
             text = hit.text,
             language = hit.language,
             tokens = hit.tokens,
             providers = hit.providers,
             lineStart = hit.offsets?.getOrNull(0) ?: 0,
             lineEnd = hit.offsets?.getOrNull(1) ?: 0
           )
         }

       val totalHits = result.hits.size
       val returned = hits.size
       val hasMore = (request.offset + request.k) < totalHits

       val elapsed = System.currentTimeMillis() - startTime

       QueryResponse(
         hits = hits,
         pagination = PaginationInfo(
           offset = request.offset,
           returned = returned,
           totalHits = totalHits,
           hasMore = hasMore
         ),
         metadata = QueryMetadata(
           queryTime = elapsed,
           providers = result.metadata.providers  // {semantic: 15, ...}
         ),
         error = null
       )
     }
   }
   ```

2. **Add endpoint** to `web/routes/QueryRoutes.kt`:
   ```kotlin
   fun Route.queryApiRoutes(
     contextModule: ContextModule
   ) {
     val queryService = QueryService(contextModule)

     route("/api/query") {
       post {
         try {
           val request = call.receive<QueryRequest>()
           val result = queryService.executeQuery(request)

           if (result.isSuccess) {
             call.respondJson(result.getOrNull()!!)
           } else {
             val error = result.exceptionOrNull()
             call.respondJson(
               mapOf(
                 "error" to (error?.message ?: "Query execution failed"),
                 "hint" to when (error?.message) {
                   "Query must be at least 2 characters" ->
                     "Enter a query with at least 2 characters (e.g., 'auth JWT')"
                   "Index not ready" ->
                     "Context index is still building. Please wait or trigger a refresh."
                   else -> "Check Query Tips in the UI for correct syntax"
                 }
               ),
               status = HttpStatusCode.BadRequest
             )
           }
         } catch (e: IllegalArgumentException) {
           call.respondJson(
             mapOf("error" to e.message),
             status = HttpStatusCode.BadRequest
           )
         } catch (e: Exception) {
           logger.error("Query error: ${e.message}", e)
           call.respondJson(
             mapOf("error" to "Query execution failed"),
             status = HttpStatusCode.InternalServerError
           )
         }
       }
     }
   }
   ```

3. **Wire into WebServer.kt**:
   ```kotlin
   routing {
     queryRoutes()
     resultActionRoutes()
     queryApiRoutes(contextModule)
     // ... other routes
   }
   ```

4. **Request/response example**:
   ```
   POST /api/query
   Content-Type: application/json

   {
     "query": "authentication JWT token",
     "k": 20,
     "maxTokens": 6000,
     "paths": ["src/main"],
     "languages": ["kotlin"],
     "kinds": [],
     "excludePatterns": ["test/"],
     "offset": 0
   }

   Response (200 OK):
   {
     "hits": [
       {
         "chunkId": 123,
         "filePath": "src/main/kotlin/auth/JwtValidator.kt",
         "score": 0.85,
         "kind": "CODE_CLASS",
         "text": "class JwtValidator { ... }",
         "language": "kotlin",
         "tokens": 245,
         "providers": ["semantic", "symbol"],
         "lineStart": 42,
         "lineEnd": 67
       },
       ...
     ],
     "pagination": {
       "offset": 0,
       "returned": 20,
       "totalHits": 145,
       "hasMore": true
     },
     "metadata": {
       "queryTime": 234,
       "providers": {
         "semantic": 15,
         "symbol": 3,
         "fulltext": 2
       }
     },
     "error": null
   }
   ```

**Dependencies**: Task 1.5 (actions)

**Files**:
- New: `web/services/QueryService.kt`
- Modify: `web/routes/QueryRoutes.kt`
- Modify: `web/WebServer.kt`

**Acceptance Criteria**:
- [ ] POST `/api/query` returns 200 OK with valid response format
- [ ] Query validation returns 400 Bad Request with helpful message
- [ ] Pagination offsets work correctly
- [ ] Provider breakdown included in metadata
- [ ] Timing metrics captured
- [ ] Error responses include hints

---

### Task 1.7 – Add Error/Empty State Handling & Messages

**Objective**: Graceful error messages and empty result states

**Output**:
- Empty state HTML template
- Error state HTML template
- Validation error messages
- Helpful UI hints & tips

**Implementation**:

1. **Create** `src/main/kotlin/com/orchestrator/web/pages/ErrorStatePage.kt`:
   ```kotlin
   package com.orchestrator.web.pages

   fun emptyStateHTML(): String = """
     <div class="empty-state">
       <div class="empty-icon">🔍</div>
       <h3>No results found</h3>
       <p>Try the following to get better results:</p>
       <ul>
         <li><strong>Shorter queries:</strong> Use 2-5 keywords instead of full sentences</li>
         <li><strong>Specific terms:</strong> "JWT validation" is better than "authentication stuff"</li>
         <li><strong>Remove filters:</strong> Untick language/kind filters to broaden search</li>
         <li><strong>Check index:</strong> Go to Index Status to verify files are indexed</li>
       </ul>

       <details class="query-tips">
         <summary>📝 Query Tips & Examples</summary>
         <div class="tips-content">
           <h4>✓ Good Queries (Short & Specific)</h4>
           <ul>
             <li>"authentication JWT token"</li>
             <li>"database connection pool"</li>
             <li>"error handling exception"</li>
             <li>"HTTP request handler"</li>
           </ul>

           <h4>✗ Bad Queries (Questions or Long)</h4>
           <ul>
             <li>❌ "how does authentication work?"</li>
             <li>❌ "show me all the authentication code"</li>
             <li>❌ "where are errors from the client handled?"</li>
             <li>❌ "what is the purpose of ignore patterns"</li>
           </ul>

           <h4>🎯 Why It Matters</h4>
           <p>The search engine is optimized for short, keyword-based queries similar to grep.
              It searches code, not answers questions naturally.</p>
         </div>
       </details>
     </div>
   """.trimIndent()

   fun errorStateHTML(
     error: String,
     hint: String? = null,
     showRetry: Boolean = true
   ): String = """
     <div class="error-state">
       <div class="error-icon">⚠️</div>
       <h3>Query Error</h3>
       <p class="error-message">$error</p>
       ${if (hint != null) "<p class=\"error-hint\">💡 $hint</p>" else ""}
       ${if (showRetry) """
         <button hx-post="/api/query" class="btn-retry">
           Retry Query
         </button>
       """.trimIndent() else ""}
     </div>
   """.trimIndent()

   fun validationErrorHTML(
     field: String,
     message: String
   ): String = """
     <div class="validation-error" data-field="$field">
       <span class="error-icon">❌</span>
       <span class="error-text">$message</span>
     </div>
   """.trimIndent()

   fun warningBannerHTML(
     title: String,
     message: String,
     action: String? = null,
     actionUrl: String? = null
   ): String = """
     <div class="warning-banner">
       <span class="warning-icon">⚠️</span>
       <div class="warning-content">
         <strong>$title</strong>
         <p>$message</p>
       </div>
       ${if (action != null && actionUrl != null) """
         <a href="$actionUrl" class="btn-action">$action →</a>
       """.trimIndent() else ""}
       <button class="btn-close" onclick="this.parentElement.remove()">✕</button>
     </div>
   """.trimIndent()
   ```

2. **Styling** (add to query-console.css):
   ```css
   .empty-state,
   .error-state {
     text-align: center;
     padding: 48px 24px;
     color: #666;
   }

   .empty-icon,
   .error-icon {
     font-size: 3rem;
     margin: 16px 0;
     opacity: 0.7;
   }

   .empty-state h3,
   .error-state h3 {
     color: #333;
     margin: 16px 0 8px 0;
   }

   .empty-state p,
   .error-state p {
     margin: 8px 0;
     line-height: 1.5;
   }

   .empty-state ul {
     text-align: left;
     display: inline-block;
     margin: 16px 0;
   }

   .empty-state li {
     margin: 6px 0;
   }

   .query-tips {
     margin-top: 24px;
     padding: 16px;
     background: #f9f9f9;
     border-radius: 6px;
     cursor: pointer;
   }

   .query-tips summary {
     font-weight: 600;
     color: #0066cc;
     user-select: none;
   }

   .query-tips summary:hover {
     color: #0052a3;
   }

   .tips-content {
     margin-top: 16px;
     text-align: left;
     line-height: 1.6;
   }

   .tips-content h4 {
     margin: 12px 0 6px 0;
     color: #333;
   }

   .tips-content ul {
     margin: 8px 0 16px 20px;
   }

   .tips-content li {
     margin: 4px 0;
   }

   .error-state {
     padding: 32px 24px;
     background: #fff8f8;
     border: 1px solid #ffcccc;
     border-radius: 6px;
   }

   .error-message {
     color: #cc0000;
     font-weight: 600;
     margin: 12px 0;
   }

   .error-hint {
     color: #666;
     font-size: 0.9rem;
     margin-top: 8px;
   }

   .btn-retry {
     margin-top: 16px;
     padding: 10px 20px;
     background: #0066cc;
     color: white;
     border: none;
     border-radius: 4px;
     cursor: pointer;
   }

   .btn-retry:hover {
     background: #0052a3;
   }

   .validation-error {
     color: #cc0000;
     font-size: 0.85rem;
     margin-top: 4px;
     display: flex;
     align-items: center;
     gap: 6px;
   }

   .warning-banner {
     display: flex;
     align-items: center;
     gap: 12px;
     padding: 12px 16px;
     background: #fff4e6;
     border: 1px solid #ffc966;
     border-radius: 6px;
     margin: 16px 0;
     position: relative;
   }

   .warning-icon {
     font-size: 1.5rem;
     flex-shrink: 0;
   }

   .warning-content {
     flex: 1;
     text-align: left;
   }

   .warning-content strong {
     display: block;
     color: #cc6600;
     margin-bottom: 4px;
   }

   .warning-content p {
     margin: 0;
     color: #666;
     font-size: 0.9rem;
   }

   .btn-action {
     padding: 6px 12px;
     background: #cc6600;
     color: white;
     border: none;
     border-radius: 3px;
     cursor: pointer;
     font-size: 0.85rem;
     white-space: nowrap;
   }

   .btn-action:hover {
     background: #994400;
   }

   .btn-close {
     position: absolute;
     top: 8px;
     right: 8px;
     border: none;
     background: none;
     cursor: pointer;
     font-size: 1.2rem;
     opacity: 0.6;
   }

   .btn-close:hover {
     opacity: 1;
   }
   ```

3. **Integration** in `web/routes/QueryRoutes.kt`:
   ```kotlin
   // When returning empty results
   if (result.hits.isEmpty()) {
     call.respondText(emptyStateHTML(), ContentType.Text.Html)
   }

   // When query fails
   call.respondText(
     errorStateHTML(
       error = error.message ?: "Unknown error",
       hint = hint
     ),
     ContentType.Text.Html
   )

   // Token budget warning
   if (response.metadata.queryTime > 5000) {
     val warning = warningBannerHTML(
       title = "Slow Query",
       message = "Query took ${response.metadata.queryTime}ms. Consider reducing K or adding more filters.",
       action = "Learn more",
       actionUrl = "/docs/context-explorer#performance"
     )
   }
   ```

**Dependencies**: Task 1.6 (API exists)

**Files**:
- New: `web/pages/ErrorStatePage.kt`
- Modify: `web/routes/QueryRoutes.kt`
- Modify: `resources/static/css/query-console.css`

**Acceptance Criteria**:
- [ ] Empty state displays helpful tips & examples
- [ ] Error state shows error + recovery hint
- [ ] Validation errors show inline near input
- [ ] Warning banners dismissible
- [ ] All states are visually distinct

---

### Task 1.8 – Wire Frontend Event Handling & Pagination

**Objective**: HTMX event handlers, loading spinners, result pagination

**Output**:
- Interactive result list with pagination
- Loading indicators
- Keyboard shortcuts
- Filter memory

**Implementation**:

1. **Main query-console.js** enhancements:
   ```javascript
   // Initialize on page load
   document.addEventListener('DOMContentLoaded', function() {
     restoreFiltersFromLocalStorage();
     setupKeyboardShortcuts();
     setupHTMXListeners();
     restoreLastQuery();
   });

   // Keyboard shortcuts
   function setupKeyboardShortcuts() {
     document.addEventListener('keydown', (e) => {
       // Ctrl/Cmd + K → Focus query input
       if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
         e.preventDefault();
         document.getElementById('query-input').focus();
       }

       // Ctrl/Cmd + Enter → Run query
       if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
         e.preventDefault();
         if (document.activeElement === document.getElementById('query-input')) {
           runQuery();
         }
       }

       // Escape → Close graph panel / clear
       if (e.key === 'Escape') {
         const panel = document.getElementById('graph-panel');
         if (panel && panel.style.display !== 'none') {
           panel.style.display = 'none';
         }
       }

       // ? → Show keyboard shortcuts help
       if (e.key === '?' && !isInputFocused()) {
         showShortcutsModal();
       }
     });
   }

   function isInputFocused() {
     const tag = document.activeElement.tagName;
     return tag === 'INPUT' || tag === 'TEXTAREA';
   }

   // Run query
   function runQuery() {
     const query = document.getElementById('query-input').value.trim();

     // Validation
     if (query.length < 2) {
       showError('Query must be at least 2 characters');
       return;
     }

     // Collect filters
     const filters = collectFilters();

     // Save to localStorage
     saveFiltersToLocalStorage(filters);
     localStorage.setItem('lastQuery', query);

     // Show loading spinner
     const resultsDiv = document.getElementById('results');
     resultsDiv.innerHTML = '<div class="loading"><div class="spinner"></div> Searching...</div>';

     // Build request
     const request = {
       query: query,
       k: parseInt(document.getElementById('k-slider').value),
       maxTokens: parseInt(document.getElementById('tokens-slider').value),
       paths: filters.paths.length > 0 ? filters.paths : undefined,
       languages: filters.languages,
       kinds: filters.kinds,
       excludePatterns: filters.exclude.length > 0 ? filters.exclude : undefined,
       offset: 0
     };

     // Fetch results
     fetch('/api/query', {
       method: 'POST',
       headers: {
         'Content-Type': 'application/json'
       },
       body: JSON.stringify(request)
     })
       .then(resp => {
         if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
         return resp.json();
       })
       .then(data => {
         if (data.error) {
           resultsDiv.innerHTML = errorStateHTML(data.error, data.hint);
         } else if (data.hits.length === 0) {
           resultsDiv.innerHTML = emptyStateHTML();
         } else {
           renderResults(data, resultsDiv);
         }
       })
       .catch(err => {
         resultsDiv.innerHTML = errorStateHTML(
           'Network error: ' + err.message,
           'Check your connection and try again'
         );
       });
   }

   function collectFilters() {
     return {
       paths: document.getElementById('paths-filter').value
         .split('\n')
         .map(p => p.trim())
         .filter(p => p.length > 0),
       languages: Array.from(
         document.querySelectorAll('input[name="language"]:checked')
       ).map(x => x.value),
       kinds: Array.from(
         document.querySelectorAll('input[name="kind"]:checked')
       ).map(x => x.value),
       exclude: document.getElementById('exclude-filter').value
         .split('\n')
         .map(p => p.trim())
         .filter(p => p.length > 0)
     };
   }

   // Render results with pagination
   function renderResults(data, container) {
     const html = `
       <div class="results-summary">
         <span>Found ${data.pagination.totalHits} results in ${data.metadata.queryTime}ms</span>
         <span class="providers-breakdown">
           ${Object.entries(data.metadata.providers)
             .map(([provider, count]) => `${provider}: ${count}`)
             .join(' | ')}
         </span>
       </div>
       <div id="results-list">
         ${data.hits.map(hit => renderResultCard(hit, data.hits.length)).join('')}
       </div>
       ${data.pagination.hasMore ? `
         <div class="load-more-section">
           <button class="btn-load-more" onclick="loadMoreResults(${data.pagination.offset + data.pagination.returned})">
             Load More Results (${Math.min(20, data.pagination.totalHits - data.pagination.returned)})
           </button>
         </div>
       ` : ''}
     `;
     container.innerHTML = html;
   }

   function renderResultCard(hit, totalHits) {
     const highlightedText = highlightQueryTerms(
       hit.text,
       document.getElementById('query-input').value
     );

     return `
       <div class="result-card" data-chunk-id="${hit.chunkId}">
         <div class="result-header">
           <span class="path" onclick="openInEditor('${hit.filePath}', ${hit.lineStart})"
                 title="${hit.filePath}">${hit.filePath}</span>
           <span class="score">${hit.score.toFixed(2)}</span>
           <span class="kind">${hit.kind}</span>
         </div>
         <div class="snippet">
           <pre><code>${highlightedText}</code></pre>
         </div>
         <div class="metadata">
           <span class="language">${hit.language}</span>
           <span class="tokens">${hit.tokens} tokens</span>
           <span class="providers">${hit.providers.join(', ')}</span>
         </div>
         <div class="actions">
           <button class="btn-action" onclick="openInEditor('${hit.filePath}', ${hit.lineStart})">
             📝 Open
           </button>
           <button class="btn-action" onclick="copySnippet('${hit.text.replace(/"/g, '\\"').replace(/\n/g, '\\n')}')">
             📋 Copy
           </button>
           <button class="btn-action" onclick="showRelatedGraph(${hit.chunkId})">
             🔗 Related
           </button>
         </div>
       </div>
     `;
   }

   function highlightQueryTerms(text, query) {
     const terms = query.split(/\s+/).filter(t => t.length > 0);
     let result = text
       .replace(/</g, '&lt;')
       .replace(/>/g, '&gt;');

     terms.forEach(term => {
       const regex = new RegExp(`\\b${term}\\b`, 'gi');
       result = result.replace(regex, `<mark class="highlight">$&</mark>`);
     });

     return result;
   }

   function loadMoreResults(offset) {
     const query = document.getElementById('query-input').value;
     const filters = collectFilters();
     const k = parseInt(document.getElementById('k-slider').value);
     const maxTokens = parseInt(document.getElementById('tokens-slider').value);

     const request = {
       query: query,
       k: k,
       maxTokens: maxTokens,
       paths: filters.paths.length > 0 ? filters.paths : undefined,
       languages: filters.languages,
       kinds: filters.kinds,
       excludePatterns: filters.exclude.length > 0 ? filters.exclude : undefined,
       offset: offset
     };

     fetch('/api/query', {
       method: 'POST',
       headers: { 'Content-Type': 'application/json' },
       body: JSON.stringify(request)
     })
       .then(resp => resp.json())
       .then(data => {
         const resultsList = document.getElementById('results-list');
         data.hits.forEach(hit => {
           const card = document.createElement('div');
           card.innerHTML = renderResultCard(hit, data.hits.length);
           resultsList.appendChild(card.firstElementChild);
         });

         // Replace load-more button
         const loadMoreBtn = document.querySelector('.load-more-section');
         if (data.pagination.hasMore) {
           loadMoreBtn.innerHTML = `
             <button class="btn-load-more" onclick="loadMoreResults(${data.pagination.offset + data.pagination.returned})">
               Load More Results
             </button>
           `;
         } else {
           loadMoreBtn.remove();
         }
       });
   }

   // Action handlers
   function openInEditor(filePath, lineStart) {
     const vscodeUrl = `vscode://file${filePath}:${lineStart}:1`;
     window.location.href = vscodeUrl;
   }

   function copySnippet(text) {
     navigator.clipboard.writeText(text).then(() => {
       showToast('✓ Copied to clipboard');
     });
   }

   function showRelatedGraph(chunkId) {
     const panel = document.getElementById('graph-panel');
     panel.style.display = 'block';
     panel.innerHTML = '<div class="loading">Loading graph...</div>';

     fetch(`/api/graph/${chunkId}`)
       .then(resp => resp.json())
       .then(data => {
         renderGraphPanel(data, panel);
       });
   }

   // Show toast notification
   function showToast(message) {
     const toast = document.createElement('div');
     toast.className = 'toast show';
     toast.textContent = message;
     document.body.appendChild(toast);

     setTimeout(() => {
       toast.classList.remove('show');
       setTimeout(() => toast.remove(), 300);
     }, 2000);
   }

   function showError(message) {
     showToast('❌ ' + message);
   }

   // Shortcuts modal
   function showShortcutsModal() {
     const modal = document.createElement('div');
     modal.className = 'modal';
     modal.innerHTML = `
       <div class="modal-content">
         <div class="modal-header">
           <h3>Keyboard Shortcuts</h3>
           <button class="btn-close" onclick="this.parentElement.parentElement.remove()">✕</button>
         </div>
         <div class="modal-body">
           <table class="shortcuts-table">
             <tr><td><kbd>Ctrl+K</kbd></td><td>Focus search input</td></tr>
             <tr><td><kbd>Ctrl+Enter</kbd></td><td>Run query</td></tr>
             <tr><td><kbd>Esc</kbd></td><td>Close graph panel</td></tr>
             <tr><td><kbd>?</kbd></td><td>Show this help</td></tr>
           </table>
         </div>
       </div>
     `;
     document.body.appendChild(modal);

     // Close on background click
     modal.addEventListener('click', (e) => {
       if (e.target === modal) modal.remove();
     });
   }

   // Save/restore filters
   function saveFiltersToLocalStorage(filters) {
     localStorage.setItem('queryFilters', JSON.stringify(filters));
   }

   function restoreFiltersFromLocalStorage() {
     const stored = localStorage.getItem('queryFilters');
     if (!stored) return;

     try {
       const filters = JSON.parse(stored);
       if (filters.paths) {
         document.getElementById('paths-filter').value = filters.paths.join('\n');
       }
       if (filters.languages) {
         filters.languages.forEach(lang => {
           const checkbox = document.querySelector(`input[name="language"][value="${lang}"]`);
           if (checkbox) checkbox.checked = true;
         });
       }
       if (filters.kinds) {
         filters.kinds.forEach(kind => {
           const checkbox = document.querySelector(`input[name="kind"][value="${kind}"]`);
           if (checkbox) checkbox.checked = true;
         });
       }
       if (filters.exclude) {
         document.getElementById('exclude-filter').value = filters.exclude.join('\n');
       }
     } catch (e) {
       console.error('Failed to restore filters:', e);
     }
   }

   function restoreLastQuery() {
     const lastQuery = localStorage.getItem('lastQuery');
     if (lastQuery) {
       document.getElementById('query-input').value = lastQuery;
     }
   }

   // HTMX event setup
   function setupHTMXListeners() {
     document.addEventListener('htmx:afterSwap', (e) => {
       if (e.detail.xhr.responseURL.includes('/api/query')) {
         // Results loaded
         const resultsDiv = document.getElementById('results');
         resultsDiv.classList.remove('loading');
       }
     });
   }
   ```

2. **Add loading spinner styling** (query-console.css):
   ```css
   .loading {
     text-align: center;
     padding: 48px 24px;
     color: #666;
   }

   .spinner {
     display: inline-block;
     width: 40px;
     height: 40px;
     border: 4px solid #f3f3f3;
     border-top: 4px solid #0066cc;
     border-radius: 50%;
     animation: spin 1s linear infinite;
   }

   @keyframes spin {
     0% { transform: rotate(0deg); }
     100% { transform: rotate(360deg); }
   }

   .results-summary {
     display: flex;
     justify-content: space-between;
     align-items: center;
     padding: 12px 16px;
     background: #f0f8ff;
     border-bottom: 1px solid #ddd;
     border-radius: 4px 4px 0 0;
     font-size: 0.9rem;
     color: #666;
   }

   .providers-breakdown {
     font-size: 0.85rem;
     color: #888;
   }

   .modal {
     position: fixed;
     top: 0;
     left: 0;
     right: 0;
     bottom: 0;
     background: rgba(0, 0, 0, 0.5);
     display: flex;
     align-items: center;
     justify-content: center;
     z-index: 1000;
   }

   .modal-content {
     background: white;
     border-radius: 8px;
     padding: 24px;
     max-width: 400px;
     box-shadow: 0 10px 40px rgba(0, 0, 0, 0.3);
   }

   .modal-header {
     display: flex;
     justify-content: space-between;
     align-items: center;
     margin-bottom: 16px;
   }

   .modal-header h3 {
     margin: 0;
   }

   .shortcuts-table {
     width: 100%;
     border-collapse: collapse;
   }

   .shortcuts-table tr {
     border-bottom: 1px solid #eee;
   }

   .shortcuts-table td {
     padding: 8px;
     text-align: left;
   }

   .shortcuts-table kbd {
     background: #f0f0f0;
     padding: 2px 6px;
     border-radius: 3px;
     font-family: monospace;
     font-size: 0.85rem;
   }
   ```

**Dependencies**: Tasks 1.6, 1.7 (API and error states exist)

**Files**:
- Modify: `resources/static/js/query-console.js`
- Modify: `resources/static/css/query-console.css`
- Modify: `web/pages/QueryConsolePage.kt` (reference JS/CSS)

**Acceptance Criteria**:
- [ ] Run button fetches and displays results
- [ ] Pagination "Load More" appends results correctly
- [ ] Keyboard shortcuts work (Ctrl+K, Ctrl+Enter, Esc, ?)
- [ ] Filters save/restore to localStorage
- [ ] Loading spinner shows during fetch
- [ ] Toast notifications appear on actions
- [ ] Keyboard shortcuts help modal displays

---

## MILESTONE 2 – Related Graph Preview

*(See detailed task breakdown above)*

**Tasks**: 2.1 through 2.6 (6 tasks, ~2-3 days)

---

## MILESTONE 3 – Quality/Config Feedback

*(See detailed task breakdown above)*

**Tasks**: 3.1 through 3.4 (4 tasks, ~1-2 days)

---

## Dependencies & Integration

### Feature Toggle
Add to `fusionagent.toml`:
```toml
[dashboard]
contextExplorer.enabled = true
```

### Database Schema
Ensure the following tables exist:
- `chunks` – Code chunks (id, file_path, language, kind, text, token_count)
- `chunk_embeddings` – Vector embeddings (chunk_id, embedding_vector)
- `chunk_links` – Code references (source_chunk_id, target_chunk_id, link_type)
- `chunk_symbols` – Symbol index (chunk_id, symbol_name, symbol_type)
- `chunk_search_index` – Full-text index (for BM25 search)

### Configuration
Update `fusionagent_config_docs.md` to document:
- Dashboard context explorer settings
- Query syntax (short keywords, not questions)
- Filter examples
- Performance tips

### Dependencies to Add
- None for M1 (existing QueryContextTool)
- Vis.js 5.x for graph visualization (M2)

### Auth & Security
- All endpoints inherit dashboard auth (no separate login)
- Rate limiting: Recommended 10 queries/minute per session to prevent abuse
- Input validation: Query min 2 chars, K ≤ 100, maxTokens ≤ 20000

---

## Estimated Effort & Timeline

| Milestone | Tasks | Days | Risk | Notes |
|-----------|-------|------|------|-------|
| **M1 – UI Console** | 8 | 2-3 | Low | No backend changes needed, reuses QueryContextTool |
| **M2 – Graph Preview** | 6 | 2-3 | Medium | Vis.js integration, link table queries |
| **M3 – Quality/Feedback** | 4 | 1-2 | Low | Mostly UX polish |
| **Total** | **18** | **5-8** | **Low-Medium** | Can be split across sessions |

### Parallel Work
- M1 tasks 1.3-1.8 can work in parallel (independent UI components)
- M2 tasks 2.1-2.3 and 2.4 can start while M1 is finishing
- M3 tasks are optional polish and can run concurrently

### MVP Scope (if time-limited)
- M1 only: Delivers search console + result cards (5 days)
- Defer M2 (graph) and M3 (polish) to future session

---

## Next Steps

1. ✅ Review implementation plan
2. Start Task 1.1 (Design) for final approval
3. Begin Task 1.2 (Page Route) – foundational work
4. Parallelize Tasks 1.3-1.8 once foundations are in place
5. Merge M1 features before starting M2

**Ready to begin? Start with Task 1.1 or jump to a specific task.**