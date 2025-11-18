# Context Explorer – UI Design Specification

**Task**: 1.1 – Design Console Layout & Components  
**Status**: Complete  
**Created**: 2025-01-15  

---

## Overview

This document specifies the UI/UX design for the Context Explorer console, a query interface embedded in the web dashboard that enables semantic code search with rich result visualization.

---

## Component Hierarchy

```
Context Explorer Page
├── Navigation Bar (shared component)
├── Main Content
│   ├── Page Header
│   │   ├── Title & Description
│   │   └── Quick Actions
│   ├── Search Section
│   │   ├── Query Input Bar
│   │   ├── Action Buttons (Run, Clear)
│   │   └── Filter Toggle
│   ├── Filter Panel (collapsible)
│   │   ├── Path Filters
│   │   ├── Language Checkboxes
│   │   ├── Kind Checkboxes
│   │   ├── Exclude Patterns
│   │   ├── Max Results Slider
│   │   ├── Max Tokens Slider
│   │   └── Filter Actions (Reset, Save)
│   ├── Results Container
│   │   ├── Status Bar (query stats)
│   │   ├── Result Cards Grid
│   │   └── Pagination Controls
│   └── Related Graph Panel (side panel, hidden by default)
│       ├── Graph Visualization (Vis.js)
│       ├── Legend
│       └── Close Button
└── Footer (shared component)
```

---

## Layout Mockups

### 1. Page Header

```
┌─────────────────────────────────────────────────────────────────┐
│ 🔍 Context Explorer                                             │
│ Search your codebase with semantic understanding                │
│                                                                  │
│ [💾 Saved Queries ▼]                                           │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Search Bar & Filter Toggle

```
┌─────────────────────────────────────────────────────────────────┐
│ [🔍 Search code... (e.g., 'authentication JWT token')_________] │
│                                                                  │
│ [▶ Run Query]  [✕ Clear]  [≡ Filters ▼]                        │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Filter Panel (Expanded)

```
┌─────────────────────────────────────────────────────────────────┐
│ Filters                                                    [✕]  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│ Paths (one per line):                                           │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ src/main/kotlin                                             │ │
│ │ src/test/kotlin                                             │ │
│ └─────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ Languages:                                                       │
│ ☑ Kotlin  ☑ Java  ☐ Python  ☐ JavaScript  ☐ TypeScript        │
│ ☐ Markdown  ☐ Document                                         │
│                                                                  │
│ Chunk Kinds:                                                     │
│ ☑ CODE_CLASS  ☑ CODE_FUNCTION  ☑ CODE_METHOD                   │
│ ☐ MARKDOWN_SECTION  ☐ PARAGRAPH  ☐ DOCUMENT                    │
│                                                                  │
│ Exclude Patterns (one per line):                                │
│ ┌─────────────────────────────────────────────────────────────┐ │
│ │ *Test.kt                                                    │ │
│ │ build/                                                      │ │
│ └─────────────────────────────────────────────────────────────┘ │
│                                                                  │
│ Max Results: [━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━] 20│
│              1                                              100 │
│                                                                  │
│ Max Tokens:  [━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━] 6K│
│              500                                          20000 │
│                                                                  │
│ [Reset Filters]  [💾 Save as Query]                            │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Result Card

```
┌─────────────────────────────────────────────────────────────────┐
│ src/main/kotlin/com/orchestrator/auth/JwtValidator.kt:42  [0.85]│
│ CODE_CLASS                                                       │
├─────────────────────────────────────────────────────────────────┤
│ class JwtValidator {                                             │
│   fun validate(token: String): Boolean {                        │
│     // Validates JWT token signature and expiration             │
├─────────────────────────────────────────────────────────────────┤
│ Kotlin | 245 tokens | semantic, symbol                          │
│ [📂 Open] [📋 Copy] [🔗 Related]                                │
└─────────────────────────────────────────────────────────────────┘
```

### 5. Status Bar

```
┌─────────────────────────────────────────────────────────────────┐
│ ⏱️ 234ms | 45 results | semantic: 30 | symbol: 10 | fulltext: 5│
└─────────────────────────────────────────────────────────────────┘
```

### 6. Pagination

```
┌─────────────────────────────────────────────────────────────────┐
│ Showing 1-20 of 145 results                                      │
│                                                                  │
│ [← Previous]  [1] [2] [3] ... [8]  [Next →]                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Specifications

### Search Input

**Element**: `<input type="text" id="query-input">`

**Attributes**:
- `placeholder`: "Search code... (e.g., 'authentication JWT token')"
- `required`: true
- `minlength`: 2
- `autocomplete`: "off"
- `hx-post`: "/api/context/query"
- `hx-trigger`: "keyup changed delay:500ms"
- `hx-target`: "#results-container"
- `hx-indicator`: "#search-spinner"

**Styling**:
- Full width
- Large font size (16px)
- Prominent border
- Focus state with blue outline

**Behavior**:
- Auto-submit on Enter key
- Debounced HTMX trigger (500ms delay)
- Show loading spinner during query
- Highlight query terms in results

---

### Filter Panel

**Element**: `<div id="filter-panel" class="filter-panel">`

**State**: Collapsed by default, toggle with button

**Sections**:

1. **Paths Filter**
   - `<textarea rows="3">` for path list
   - One path per line
   - Example: `src/main/kotlin`, `src/test/kotlin`

2. **Languages Filter**
   - Checkbox group
   - Options: Kotlin, Java, Python, JavaScript, TypeScript, Markdown, Document
   - Default: All checked

3. **Kinds Filter**
   - Checkbox group
   - Options: CODE_CLASS, CODE_FUNCTION, CODE_METHOD, MARKDOWN_SECTION, PARAGRAPH, DOCUMENT
   - Default: Code kinds checked

4. **Exclude Patterns**
   - `<textarea rows="3">` for exclusion patterns
   - One pattern per line
   - Example: `*Test.kt`, `build/`, `*.md`

5. **Max Results Slider**
   - `<input type="range" min="1" max="100" value="20">`
   - Display current value next to slider

6. **Max Tokens Slider**
   - `<input type="range" min="500" max="20000" step="500" value="6000">`
   - Display current value with K suffix (e.g., "6K")

**Actions**:
- Reset Filters: Clear all filters to defaults
- Save as Query: Open modal to save current query + filters

---

### Result Card

**Element**: `<div class="result-card">`

**Structure**:

```html
<div class="result-card" data-chunk-id="12345">
  <div class="result-card__header">
    <a href="#" class="result-card__path">
      src/main/kotlin/com/orchestrator/auth/JwtValidator.kt:42
    </a>
    <span class="result-card__score badge badge-info">0.85</span>
  </div>
  
  <div class="result-card__kind">
    <span class="badge badge-secondary">CODE_CLASS</span>
  </div>
  
  <div class="result-card__snippet">
    <pre><code class="language-kotlin">class JwtValidator {
  fun validate(token: String): Boolean {
    // Validates JWT token signature and expiration</code></pre>
  </div>
  
  <div class="result-card__metadata">
    <span class="text-muted">Kotlin | 245 tokens | semantic, symbol</span>
  </div>
  
  <div class="result-card__actions">
    <button class="btn btn-sm btn-outline-primary" 
            hx-get="/api/files/open?path=..." 
            hx-target="#modal-container">
      📂 Open
    </button>
    <button class="btn btn-sm btn-outline-secondary" 
            onclick="copyToClipboard(this)" 
            data-content="...">
      📋 Copy
    </button>
    <button class="btn btn-sm btn-outline-info" 
            hx-get="/api/context/related?chunkId=12345" 
            hx-target="#graph-panel">
      🔗 Related
    </button>
  </div>
</div>
```

**Styling**:
- Card with subtle shadow
- Hover effect (slight elevation)
- Syntax highlighting for code snippets
- Query term highlighting (yellow background)
- Truncate snippet to 3 lines max

**Actions**:
1. **Open**: Opens file in modal or external editor
2. **Copy**: Copies snippet to clipboard
3. **Related**: Opens related graph panel

---

### Status Bar

**Element**: `<div id="status-bar" class="status-bar">`

**Content**:
- Query execution time (ms)
- Total results count
- Provider breakdown (semantic, symbol, fulltext, git_history)

**Example**:
```
⏱️ 234ms | 45 results | semantic: 30 | symbol: 10 | fulltext: 5
```

**Styling**:
- Fixed to bottom of results container
- Light background
- Small font size
- Muted text color

---

### Pagination

**Element**: `<div class="pagination-controls">`

**Variants**:

1. **Load More** (infinite scroll style):
   ```
   Showing 1-20 of 145 results
   [Load More (20)]
   ```

2. **Page Numbers** (traditional):
   ```
   [← Previous] [1] [2] [3] ... [8] [Next →]
   ```

**Behavior**:
- HTMX-powered (append results for Load More)
- Preserve scroll position
- Show loading indicator during fetch

---

### Related Graph Panel

**Element**: `<div id="graph-panel" class="side-panel">`

**State**: Hidden by default, slides in from right

**Content**:
- Graph visualization (Vis.js network)
- Legend (node types, edge types)
- Close button

**Dimensions**:
- Width: 40% of viewport
- Height: 100% of viewport
- Overlay with backdrop

**Graph Elements**:
- Nodes: Code chunks (colored by kind)
- Edges: Relationships (imports, calls, references)
- Interactive: Click to view chunk details

---

## CSS Classes

### Layout Classes
- `.explorer-container`: Main container
- `.search-section`: Search bar + filters
- `.filter-panel`: Collapsible filter sidebar
- `.results-container`: Results grid
- `.side-panel`: Sliding panel for graph

### Component Classes
- `.result-card`: Individual result card
- `.result-card__header`: File path + score
- `.result-card__kind`: Chunk kind badge
- `.result-card__snippet`: Code snippet
- `.result-card__metadata`: Language, tokens, providers
- `.result-card__actions`: Action buttons
- `.status-bar`: Query stats footer
- `.pagination-controls`: Pagination UI

### State Classes
- `.is-loading`: Show loading spinner
- `.is-expanded`: Filter panel expanded
- `.is-visible`: Side panel visible
- `.is-highlighted`: Query term highlighted

---

## Interactions

### Keyboard Shortcuts
- `Enter`: Submit query
- `Ctrl+K` / `Cmd+K`: Focus search input
- `Esc`: Close filter panel / graph panel
- `Ctrl+F` / `Cmd+F`: Focus search (override browser default)

### HTMX Triggers
- Query input: `keyup changed delay:500ms`
- Run button: `click`
- Filter changes: `change`
- Pagination: `click`
- Related button: `click`

### Loading States
- Search spinner during query execution
- Skeleton cards while loading results
- Progress bar for long-running queries

---

## Accessibility

### ARIA Attributes
- `role="search"` on search section
- `role="region"` on filter panel
- `role="list"` on results container
- `role="listitem"` on result cards
- `aria-label` on all buttons
- `aria-expanded` on filter toggle
- `aria-live="polite"` on status bar

### Keyboard Navigation
- Tab order: Search → Filters → Results → Actions
- Focus visible indicators
- Skip links for screen readers

### Color Contrast
- WCAG AA compliance (4.5:1 minimum)
- High contrast mode support
- Color-blind friendly badges

---

## Responsive Design

### Breakpoints
- Desktop: 1200px+ (full layout)
- Tablet: 768px-1199px (stacked filters)
- Mobile: <768px (collapsed filters, single column results)

### Mobile Adaptations
- Full-width search input
- Bottom sheet for filters
- Single column result cards
- Simplified pagination (Load More only)
- Graph panel as full-screen overlay

---

## Acceptance Criteria

- [x] Component hierarchy documented
- [x] Layout mockups provided (ASCII art)
- [x] Result card structure specified
- [x] Action buttons & interactions listed
- [x] CSS classes defined
- [x] Accessibility requirements specified
- [x] Responsive design considerations included
- [x] HTMX integration patterns documented

---

## Next Steps

Proceed to **Task 1.2**: Create Query Console Page Route in Ktor

This design will be implemented using:
- Kotlin HTML DSL (kotlinx.html)
- HTMX for dynamic updates
- Bootstrap Litera theme (existing)
- Custom CSS for explorer-specific styles
