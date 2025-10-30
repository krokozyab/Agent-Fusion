# Web Dashboard - Implementation Plan

## Document Control
- **Project**: Agent Fusion Orchestrator - Web Dashboard
- **Version**: 1.0
- **Date**: October 18, 2025
- **Status**: Implementation Ready
- **Related**: WEB_DASHBOARD_ARCHITECTURE.md
- **Purpose**: Step-by-step implementation guide for web dashboard with HTMX and SSE

---

## Executive Summary

This implementation plan provides a structured approach to building a lightweight, server-rendered web dashboard for the Agent Fusion Orchestrator. The dashboard uses HTMX for dynamic updates, Server-Sent Events (SSE) for real-time data, and zero JavaScript build step.

**Key Metrics**:
- **Total Duration**: 10-12 weeks
- **Total Tasks**: 75 tasks across 8 phases
- **Team Size**: 2-3 developers (1 backend + 1 frontend + 1 QA)
- **Technology**: Ktor + HTMX + kotlinx.html + SSE

**Success Criteria**:
- Zero npm/webpack build step
- <100ms server response time
- <50ms HTMX swap time
- Real-time updates via SSE ddd
- Mobile responsive design
- WCAG 2.1 AA accessibility

---

## How to Use This Plan

### Task Format
```
DASH-XXX: Brief Description
├── Priority: [P0-Critical | P1-High | P2-Medium | P3-Low]
├── Estimated Time: [30min | 1h | 2h | 4h | 1day | 3days]
├── Dependencies: [DASH-YYY, DASH-ZZZ]
├── Assigned To: [Backend | Frontend | Full-Stack | QA]
├── Phase: [Phase Number-Name]
└── Component: [Web Server | HTML Rendering | SSE | Routes | UI]
```

### Execution Guidelines
1. **Follow phases sequentially** - Phase 0 must complete before Phase 1
2. **Complete all P0 tasks first** within each phase
3. **Test incrementally** - run tests after each task
4. **Commit frequently** - commit after each completed task
5. **Update task status** in project tracking system
6. **Document decisions** especially for HTMX patterns

### Technology Stack
- **Backend**: Ktor 2.3.7+ with kotlinx.html
- **Frontend**: HTMX 1.9.10+ (~14KB), Mermaid.js 10.6.1+ (~500KB)
- **CSS**: Pico CSS 2.0.6+ or custom minimal CSS
- **Real-time**: Server-Sent Events (SSE)
- **Database**: DuckDB (existing)

---

## Phase 0: Foundation & Project Setup (Week 1)

### DASH-001: Add Ktor Dependencies
**Priority**: P0  
**Time**: 1h  
**Dependencies**: None  
**Assigned To**: Backend Developer  
**Phase**: 0-Foundation  
**Component**: Build Configuration

**Description**: Add required dependencies to build.gradle.kts for web server

**Tasks**:
1. Add Ktor server dependencies:
   - `io.ktor:ktor-server-core`
   - `io.ktor:ktor-server-netty`
   - `io.ktor:ktor-server-html-builder`
   - `io.ktor:ktor-server-sse`
   - `io.ktor:ktor-server-cors`
   - `io.ktor:ktor-server-compression`
2. Add kotlinx.html dependency
3. Add ktor-server-content-negotiation for JSON API
4. Verify compatibility with existing dependencies
5. Update Gradle wrapper if needed

**Acceptance Criteria**:
- All dependencies resolve without conflicts
- `./gradlew build` succeeds
- No version conflicts with existing MCP server
- Documentation updated in README

**Files to Update**:
- `build.gradle.kts`
- `gradle.properties`

---

### DASH-002: Web Server Configuration
**Priority**: P0  
**Time**: 2h  
**Dependencies**: DASH-001  
**Assigned To**: Backend Developer  
**Phase**: 0-Foundation  
**Component**: Web Server

**Description**: Create separate Ktor server configuration for web dashboard

**Tasks**:
1. Create `web/WebServerConfig.kt` data class:
   - Port configuration (default: 8081)
   - Host binding (default: 0.0.0.0)
   - SSL configuration (optional)
   - Static files path
   - CORS settings
2. Add configuration to `application.conf`
3. Create `web/WebServerModule.kt`
4. Configure logging for web server
5. Implement graceful shutdown

**Acceptance Criteria**:
- Configuration loads from file
- Default values work out of box
- Can override with environment variables
- Separate from MCP server (port 8080)
- Graceful shutdown on server stop

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/WebServerConfig.kt`
- `src/main/kotlin/com/orchestrator/web/WebServerModule.kt`
- `src/main/resources/web-server.conf`

---

### DASH-003: Ktor Application Setup
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-002  
**Assigned To**: Backend Developer  
**Phase**: 0-Foundation  
**Component**: Web Server

**Description**: Initialize Ktor application with required plugins

**Tasks**:
1. Create `web/WebServer.kt`:
   - Initialize Ktor engine
   - Install routing plugin
   - Install SSE plugin
   - Install HTML builder plugin
   - Install CORS plugin
   - Install compression plugin
   - Install status pages
2. Add request logging middleware
3. Configure CORS for localhost development
4. Configure compression (gzip, deflate)
5. Add custom error pages (404, 500)
6. Create health check endpoint
7. Write startup/shutdown tests

**Acceptance Criteria**:
- Server starts on configured port (8081)
- All plugins installed correctly
- Health check responds at /health
- CORS headers present in responses
- Compression enabled for text content
- Error pages render correctly
- Tests verify startup sequence

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/WebServer.kt`
- `src/main/kotlin/com/orchestrator/web/plugins/`
- `tests/web/WebServerTest.kt`

---

### DASH-004: Static File Serving
**Priority**: P0  
**Time**: 2h  
**Dependencies**: DASH-003  
**Assigned To**: Backend Developer  
**Phase**: 0-Foundation  
**Component**: Web Server

**Description**: Set up static file serving for CSS, JS, and images

**Tasks**:
1. Create static files directory structure:
   - `/static/css/`
   - `/static/js/`
   - `/static/images/`
   - `/static/fonts/`
2. Configure static file routes in Ktor
3. Add cache headers for static content
4. Download and add HTMX (~14KB):
   - `htmx.min.js` (1.9.10+)
5. Download and add Mermaid.js (~500KB):
   - `mermaid.min.js` (10.6.1+)
6. Add placeholder styles.css
7. Test static file access

**Acceptance Criteria**:
- Static files accessible at /static/*
- Cache headers set correctly (1 year for versioned files)
- HTMX loads without errors
- Mermaid.js loads without errors
- 404 for missing static files
- Content-Type headers correct

**Files to Create**:
- `src/main/resources/static/js/htmx.min.js`
- `src/main/resources/static/js/mermaid.min.js`
- `src/main/resources/static/css/styles.css`
- `src/main/kotlin/com/orchestrator/web/routes/StaticRoutes.kt`

---

### DASH-005: HTML Rendering Foundation
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-003  
**Assigned To**: Frontend Developer  
**Phase**: 0-Foundation  
**Component**: HTML Rendering

**Description**: Create base HTML rendering infrastructure with kotlinx.html

**Tasks**:
1. Create `web/rendering/HtmlRenderer.kt` object
2. Create base HTML template function:
   - DOCTYPE declaration
   - `<html lang="en">`
   - `<head>` with meta tags
   - `<title>` parameterized
   - CSS includes
   - JS includes (HTMX, Mermaid)
3. Create `PageLayout.kt`:
   - Header with navigation
   - Main content area
   - Footer
   - HTMX initialization
4. Create `Fragment.kt` for partial rendering
5. Add viewport meta for mobile
6. Add favicon support
7. Write rendering tests

**Acceptance Criteria**:
- Valid HTML5 output
- All pages use consistent layout
- Mobile viewport configured
- HTMX loaded on all pages
- Mermaid loaded on all pages
- Type-safe HTML generation
- Tests verify HTML structure

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/rendering/HtmlRenderer.kt`
- `src/main/kotlin/com/orchestrator/web/rendering/PageLayout.kt`
- `src/main/kotlin/com/orchestrator/web/rendering/Fragment.kt`
- `tests/web/rendering/HtmlRendererTest.kt`

---

### DASH-006: Basic CSS Styling
**Priority**: P1  
**Time**: 4h  
**Dependencies**: DASH-004  
**Assigned To**: Frontend Developer  
**Phase**: 0-Foundation  
**Component**: UI

**Description**: Create minimal CSS framework or integrate Pico CSS

**Tasks**:
1. **Option A**: Download Pico CSS (classless, ~10KB):
   - Add pico.min.css to /static/css/
   - Configure color scheme
   - Test responsive behavior
2. **Option B**: Create custom minimal CSS:
   - Reset styles
   - Typography
   - Layout (flexbox/grid)
   - Forms
   - Tables
   - Buttons
3. Add custom overrides for:
   - Dashboard layout
   - DataTable styling
   - Status badges
   - Progress bars
4. Add dark mode support
5. Test across browsers

**Acceptance Criteria**:
- Total CSS <50KB uncompressed
- Mobile responsive (320px+)
- Works without JavaScript
- Dark mode toggle functional
- Print styles included
- Cross-browser tested (Chrome, Firefox, Safari)

**Files to Create**:
- `src/main/resources/static/css/pico.min.css` OR `base.css`
- `src/main/resources/static/css/custom.css`
- `src/main/resources/static/css/dark-mode.css`

---

### DASH-007: Browser Auto-Launch
**Priority**: P2  
**Time**: 2h  
**Dependencies**: DASH-003  
**Assigned To**: Backend Developer  
**Phase**: 0-Foundation  
**Component**: Web Server

**Description**: Implement automatic browser opening on server start

**Tasks**:
1. Create `web/BrowserLauncher.kt`
2. Implement Desktop.browse() for Java Desktop API
3. Add fallback for headless servers (no-op)
4. Add configuration flag to disable auto-launch
5. Detect OS and use appropriate browser command:
   - Windows: `cmd /c start`
   - macOS: `open`
   - Linux: `xdg-open`
6. Add delay to ensure server ready (500ms)
7. Handle errors gracefully

**Acceptance Criteria**:
- Browser opens automatically on supported systems
- Correct URL opened (http://localhost:8081)
- No crash on headless systems
- Can disable via config flag
- Error logged but doesn't break startup
- Works on Windows, macOS, Linux

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/BrowserLauncher.kt`
- `tests/web/BrowserLauncherTest.kt`

---

### DASH-008: Navigation Component
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-005  
**Assigned To**: Frontend Developer  
**Phase**: 0-Foundation  
**Component**: UI

**Description**: Create main navigation bar component

**Tasks**:
1. Create `web/components/Navigation.kt`
2. Render navigation with links:
   - Home/Dashboard
   - Tasks
   - Index Status
   - Metrics
   - Settings (future)
3. Add active state highlighting
4. Make responsive (hamburger menu on mobile)
5. Add HTMX boost to navigation links
6. Style navigation bar
7. Add breadcrumbs component

**Acceptance Criteria**:
- Navigation visible on all pages
- Active page highlighted
- Responsive design (mobile hamburger)
- HTMX boost works (no full page reload)
- Keyboard accessible (Tab navigation)
- ARIA labels present

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/Navigation.kt`
- `src/main/kotlin/com/orchestrator/web/components/Breadcrumbs.kt`
- `tests/web/components/NavigationTest.kt`

---

### DASH-009: Home Page Route
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-005, DASH-008  
**Assigned To**: Full-Stack Developer  
**Phase**: 0-Foundation  
**Component**: Routes

**Description**: Create home page with system overview

**Tasks**:
1. Create `web/routes/HomeRoutes.kt`
2. Implement GET / route:
   - Render PageLayout with navigation
   - Show system overview dashboard
   - Display quick stats (tasks, index, metrics)
   - Add recent activity feed
3. Query data from repositories
4. Render with kotlinx.html
5. Add HTMX for live updates
6. Write route tests

**Acceptance Criteria**:
- Home page renders at /
- Shows system status overview
- Quick stats displayed correctly
- Recent activity feed works
- Page loads <100ms
- Mobile responsive

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/routes/HomeRoutes.kt`
- `src/main/kotlin/com/orchestrator/web/pages/HomePage.kt`
- `tests/web/routes/HomeRoutesTest.kt`

---

### DASH-010: Health Check Endpoint
**Priority**: P0  
**Time**: 1h  
**Dependencies**: DASH-003  
**Assigned To**: Backend Developer  
**Phase**: 0-Foundation  
**Component**: Routes

**Description**: Implement comprehensive health check endpoint

**Tasks**:
1. Create `web/routes/HealthRoutes.kt`
2. Implement GET /health route:
   - Check web server status
   - Check database connectivity
   - Check EventBus status
   - Check MCP server status
   - Return JSON response
3. Add version information
4. Add uptime information
5. Return appropriate HTTP status codes
6. Write health check tests

**Acceptance Criteria**:
- /health returns 200 when healthy
- /health returns 503 when unhealthy
- JSON format matches spec
- All system components checked
- Response time <10ms
- Tests cover all scenarios

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/routes/HealthRoutes.kt`
- `tests/web/routes/HealthRoutesTest.kt`

---

## Phase 1: Tasks Page - Foundation (Week 2)

### DASH-011: Task Domain DTOs
**Priority**: P0  
**Time**: 2h  
**Dependencies**: DASH-005  
**Assigned To**: Backend Developer  
**Phase**: 1-Tasks  
**Component**: Domain

**Description**: Create data transfer objects for tasks page

**Tasks**:
1. Create `web/dto/TaskDTO.kt`:
   - Map from Task domain model
   - Include computed fields (duration, age)
   - Format timestamps for display
2. Create `web/dto/TaskListDTO.kt`:
   - Pagination info
   - Sorting info
   - Filter state
   - Total count
3. Create `web/dto/TaskDetailDTO.kt`:
   - Full task info
   - Proposals list
   - Decision info
   - Mermaid flow data
4. Add extension functions for mapping
5. Write mapping tests

**Acceptance Criteria**:
- DTOs map correctly from domain models
- Timestamps formatted consistently
- All computed fields calculated
- Tests verify all mappings
- No domain model leakage to view layer

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/dto/TaskDTO.kt`
- `src/main/kotlin/com/orchestrator/web/dto/TaskListDTO.kt`
- `src/main/kotlin/com/orchestrator/web/dto/TaskDetailDTO.kt`
- `src/main/kotlin/com/orchestrator/web/dto/Mappers.kt`
- `tests/web/dto/TaskDTOTest.kt`

---

### DASH-012: Tasks Page Route
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-011  
**Assigned To**: Backend Developer  
**Phase**: 1-Tasks  
**Component**: Routes

**Description**: Implement main tasks page route with server-side rendering

**Tasks**:
1. Create `web/routes/TaskRoutes.kt`
2. Implement GET /tasks route:
   - Parse query parameters (filters, sort, pagination)
   - Query TaskRepository
   - Map to DTOs
   - Render full HTML page
3. Handle empty state (no tasks)
4. Handle error states
5. Add proper HTTP caching headers
6. Write route tests

**Acceptance Criteria**:
- /tasks renders full page with navigation
- Query parameters parsed correctly
- Tasks displayed in table
- Empty state shows helpful message
- Error handling works
- Response time <100ms for 100 tasks
- Tests cover all scenarios

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/routes/TaskRoutes.kt`
- `tests/web/routes/TaskRoutesTest.kt`

---

### DASH-013: Task DataTable Component
**Priority**: P0  
**Time**: 1 day  
**Dependencies**: DASH-012  
**Assigned To**: Frontend Developer  
**Phase**: 1-Tasks  
**Component**: UI

**Description**: Create reusable DataTable component with HTMX

**Tasks**:
1. Create `web/components/DataTable.kt`:
   - Table header with sortable columns
   - Table body with rows
   - Pagination controls
   - Per-page size selector
   - Empty state template
2. Add HTMX attributes:
   - `hx-get` for sorting
   - `hx-target` for table body
   - `hx-swap` strategy (outerHTML)
   - `hx-indicator` for loading state
3. Add sort indicators (↑↓)
4. Style table responsively
5. Add keyboard navigation
6. Write component tests

**Acceptance Criteria**:
- Table renders correctly
- Sorting works via HTMX
- Pagination works via HTMX
- No full page reload on interaction
- Loading indicator shows during requests
- Mobile responsive (horizontal scroll)
- Accessible (ARIA labels)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/DataTable.kt`
- `src/main/kotlin/com/orchestrator/web/components/Pagination.kt`
- `tests/web/components/DataTableTest.kt`

---

### DASH-014: Task Table Fragment Route
**Priority**: P0  
**Time**: 2h  
**Dependencies**: DASH-013  
**Assigned To**: Backend Developer  
**Phase**: 1-Tasks  
**Component**: Routes

**Description**: Create fragment route for DataTable updates

**Tasks**:
1. Extend `web/routes/TaskRoutes.kt`
2. Implement GET /tasks/table route:
   - Parse query parameters
   - Query tasks
   - Render only table body fragment
   - Set appropriate headers (HX-Trigger)
3. Support all query parameters:
   - `status`, `type`, `agent`, `search`
   - `sortBy`, `sortOrder`
   - `page`, `pageSize`
4. Optimize query performance
5. Write fragment tests

**Acceptance Criteria**:
- /tasks/table returns HTML fragment only
- No full page layout rendered
- All filters work correctly
- Sorting works correctly
- Pagination works correctly
- Response time <50ms
- Tests verify fragment structure

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/routes/TaskRoutes.kt`

---

### DASH-015: Search and Filter UI
**Priority**: P1  
**Time**: 4h  
**Dependencies**: DASH-013  
**Assigned To**: Frontend Developer  
**Phase**: 1-Tasks  
**Component**: UI

**Description**: Create search and filter controls for tasks

**Tasks**:
1. Create `web/components/SearchFilter.kt`:
   - Search input with HTMX
   - Status filter dropdown
   - Type filter dropdown
   - Agent filter dropdown
   - Date range picker (optional)
2. Add HTMX attributes:
   - `hx-get` to /tasks/table
   - `hx-trigger="keyup changed delay:500ms"`
   - `hx-target="#tasks-table-body"`
   - `hx-include` for all filters
3. Add clear filters button
4. Add filter presets (optional)
5. Style filter controls
6. Add keyboard shortcuts (/)

**Acceptance Criteria**:
- Search triggers after 500ms delay
- Filters update table immediately
- Clear button resets all filters
- Loading indicator during search
- Keyboard shortcut (/) focuses search
- Mobile friendly (stacked layout)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/SearchFilter.kt`
- `src/main/resources/static/js/filter-presets.js` (optional)

---

### DASH-016: Task Row Component
**Priority**: P1  
**Time**: 3h  
**Dependencies**: DASH-013  
**Assigned To**: Frontend Developer  
**Phase**: 1-Tasks  
**Component**: UI

**Description**: Create task row component with status badges

**Tasks**:
1. Create `web/components/TaskRow.kt`:
   - Render table row with task data
   - Status badge with color coding
   - Type badge
   - Agent assignment display
   - Timestamp display (relative)
   - Action buttons (view, edit)
2. Add click handler (HTMX):
   - Click row to open detail modal
   - `hx-get="/tasks/{id}"`
   - `hx-target="#modal"`
3. Create status badge component
4. Create relative time formatter
5. Style row hover effects
6. Add accessibility attributes

**Acceptance Criteria**:
- Row displays all task info clearly
- Status badges color-coded correctly
- Click opens detail view
- Hover effect visible
- Timestamps relative (2h ago, 3d ago)
- Screen reader accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/TaskRow.kt`
- `src/main/kotlin/com/orchestrator/web/components/StatusBadge.kt`
- `src/main/kotlin/com/orchestrator/web/utils/TimeFormatters.kt`

---

### DASH-017: Pagination Component
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-013  
**Assigned To**: Frontend Developer  
**Phase**: 1-Tasks  
**Component**: UI

**Description**: Create pagination controls with HTMX

**Tasks**:
1. Create `web/components/Pagination.kt`:
   - Page numbers (1, 2, 3, ...)
   - Previous/Next buttons
   - First/Last buttons
   - Current page indicator
   - Total items display
   - Per-page selector (10, 25, 50, 100)
2. Add HTMX attributes:
   - `hx-get` with page parameter
   - `hx-target="#tasks-table-body"`
   - `hx-include` for filters
3. Show ellipsis for many pages
4. Disable buttons appropriately
5. Style pagination controls
6. Add keyboard navigation (←→)

**Acceptance Criteria**:
- Pagination controls visible
- Current page highlighted
- Previous/Next work correctly
- Per-page selector updates table
- Keyboard navigation works
- Disabled state for first/last pages

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/Pagination.kt`

---

### DASH-018: Empty and Error States
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-013  
**Assigned To**: Frontend Developer  
**Phase**: 1-Tasks  
**Component**: UI

**Description**: Create empty state and error state components

**Tasks**:
1. Create `web/components/EmptyState.kt`:
   - Icon/illustration
   - Heading
   - Description
   - Call-to-action button (optional)
2. Create `web/components/ErrorState.kt`:
   - Error icon
   - Error message
   - Retry button with HTMX
3. Create specific empty states:
   - No tasks found
   - No tasks matching filters
   - No data available
4. Style empty/error states
5. Write component tests

**Acceptance Criteria**:
- Empty states show when no data
- Error states show on failures
- Messages helpful and actionable
- Retry button works
- Visually clear and centered
- Accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/EmptyState.kt`
- `src/main/kotlin/com/orchestrator/web/components/ErrorState.kt`

---

## Phase 2: Task Detail View (Week 3)

### DASH-019: Task Detail Page Route
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-012  
**Assigned To**: Backend Developer  
**Phase**: 2-TaskDetail  
**Component**: Routes

**Description**: Create task detail page with full information

**Tasks**:
1. Extend `web/routes/TaskRoutes.kt`
2. Implement GET /tasks/:id route:
   - Query task by ID
   - Query proposals for task
   - Query decision for task
   - Generate Mermaid flow diagram data
   - Render full page or modal
3. Handle 404 for missing tasks
4. Add breadcrumb navigation
5. Write route tests

**Acceptance Criteria**:
- /tasks/:id renders detail page
- All task information displayed
- Proposals shown if present
- Decision shown if present
- 404 page for invalid IDs
- Response time <100ms
- Tests cover all scenarios

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/routes/TaskRoutes.kt`
- `src/main/kotlin/com/orchestrator/web/pages/TaskDetailPage.kt` (new)

---

### DASH-020: Task Detail Component
**Priority**: P0  
**Time**: 4h  
**Dependencies**: DASH-019  
**Assigned To**: Frontend Developer  
**Phase**: 2-TaskDetail  
**Component**: UI

**Description**: Create comprehensive task detail view component

**Tasks**:
1. Create `web/components/TaskDetail.kt`:
   - Task header (title, ID, timestamps)
   - Status section with badges
   - Description/context section
   - Metadata section (type, agent, routing)
   - Complexity/risk indicators
   - Proposals section (if present)
   - Decision section (if present)
2. Style detail sections
3. Add copy-to-clipboard for task ID
4. Add action buttons (refresh, delete)
5. Make responsive
6. Write component tests

**Acceptance Criteria**:
- All task info displayed clearly
- Sections collapsible (optional)
- Copy button works
- Action buttons functional
- Mobile responsive
- Accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/TaskDetail.kt`
- `src/main/resources/static/js/clipboard.js`

---

### DASH-021: Proposals Component
**Priority**: P1  
**Time**: 3h  
**Dependencies**: DASH-020  
**Assigned To**: Frontend Developer  
**Phase**: 2-TaskDetail  
**Component**: UI

**Description**: Create component to display agent proposals

**Tasks**:
1. Create `web/components/ProposalList.kt`:
   - List of proposals with agent info
   - Timestamp for each proposal
   - Confidence score display
   - Content preview with expand/collapse
   - Token usage display
2. Create `web/components/ProposalCard.kt`:
   - Agent name and avatar
   - Proposal content (formatted)
   - Metadata (tokens, confidence, timestamp)
   - Vote/selection indicator (if consensus)
3. Add syntax highlighting for code
4. Style proposal cards
5. Write component tests

**Acceptance Criteria**:
- All proposals displayed
- Agent info visible
- Confidence scores shown
- Content readable (formatted)
- Expand/collapse works
- Token usage accurate

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/ProposalList.kt`
- `src/main/kotlin/com/orchestrator/web/components/ProposalCard.kt`

---

### DASH-022: Decision Component
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-020  
**Assigned To**: Frontend Developer  
**Phase**: 2-TaskDetail  
**Component**: UI

**Description**: Create component to display consensus decision

**Tasks**:
1. Create `web/components/Decision.kt`:
   - Decision result/outcome
   - Consensus strategy used
   - Token savings display
   - Winning proposal highlight
   - Decision rationale
   - Timestamp
2. Style decision section
3. Add visual indicator for consensus type
4. Write component tests

**Acceptance Criteria**:
- Decision info clear and complete
- Winning proposal highlighted
- Token savings prominently displayed
- Rationale readable
- Visual design consistent

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/Decision.kt`

---

### DASH-023: Mermaid Flow Diagram Integration
**Priority**: P1  
**Time**: 1 day  
**Dependencies**: DASH-019  
**Assigned To**: Frontend Developer  
**Phase**: 2-TaskDetail  
**Component**: UI

**Description**: Generate and render Mermaid sequence diagrams that visualize task workflow

**Tasks**:
1. Create `web/utils/MermaidGenerator.kt`:
   - Generate Mermaid `sequenceDiagram` syntax from task data
   - Show task creation → routing decision
   - Show proposal submission
   - Show consensus process (if applicable)
   - Show decision outcome
2. Add Mermaid rendering:
   - Include mermaid.min.js
   - Initialize Mermaid on page load
   - Render sequence diagram in detail view
3. Handle different routing strategies:
   - SOLO: linear sequence between task, single agent, completion
   - CONSENSUS: fan-in sequence showing multiple agent messages before consensus
   - SEQUENTIAL: chained sequence steps
   - PARALLEL: note-style blocks to represent concurrent agent lifelines
4. Add diagram export (SVG, PNG)
5. Style diagram container
6. Write generator tests

**Acceptance Criteria**:
- Diagrams render correctly
- All routing strategies supported
- Diagrams match actual workflow
- Export functionality works
- Diagrams responsive (scale to container)
- Tests verify syntax generation

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/utils/MermaidGenerator.kt`
- `src/main/resources/static/js/mermaid-init.js`
- `tests/web/utils/MermaidGeneratorTest.kt`

---

### DASH-024: Task Detail Modal (Optional)
**Priority**: P2  
**Time**: 3h  
**Dependencies**: DASH-020  
**Assigned To**: Frontend Developer  
**Phase**: 2-TaskDetail  
**Component**: UI

**Description**: Create modal version of task detail for quick view

**Tasks**:
1. Create `web/components/Modal.kt`:
   - Modal container with backdrop
   - Close button (X)
   - Modal header, body, footer
   - Keyboard handling (Escape key)
2. Integrate with task table:
   - Click row opens modal
   - HTMX loads content: `hx-get="/tasks/:id/modal"`
3. Add modal route:
   - Render task detail in modal format
   - Return HTML fragment
4. Style modal overlay
5. Add transitions (fade in/out)
6. Write modal tests

**Acceptance Criteria**:
- Modal opens on row click
- Content loads via HTMX
- Close button works
- Escape key closes modal
- Backdrop click closes modal
- Smooth animations
- Accessible (focus trap)

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/Modal.kt`
- `src/main/resources/static/css/modal.css`
- `src/main/resources/static/js/modal.js`

---

## Phase 3: SSE Real-Time Updates (Week 4)

### DASH-025: SSE Infrastructure
**Priority**: P0  
**Time**: 4h  
**Dependencies**: DASH-003  
**Assigned To**: Backend Developer  
**Phase**: 3-SSE  
**Component**: SSE

**Description**: Set up Server-Sent Events infrastructure in Ktor

**Tasks**:
1. Create `web/sse/SSEManager.kt`:
   - Manage active SSE connections
   - Subscribe/unsubscribe connections
   - Broadcast events to subscribers
   - Track connection health
   - Handle connection drops
2. Create `web/sse/SSEEvent.kt`:
   - Event type enum
   - Event data wrapper
   - HTML fragment payload
   - Event ID generation
3. Implement connection lifecycle:
   - Connection opened
   - Send initial "connected" event
   - Keep-alive ping (every 30s)
   - Connection closed cleanup
4. Add connection monitoring
5. Write SSE tests

**Acceptance Criteria**:
- SSE connections managed efficiently
- Events broadcast to all subscribers
- Connections cleaned up on close
- Keep-alive prevents timeout
- Memory leaks prevented
- Tests verify event delivery

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/sse/SSEManager.kt`
- `src/main/kotlin/com/orchestrator/web/sse/SSEEvent.kt`
- `src/main/kotlin/com/orchestrator/web/sse/SSEConnection.kt`
- `tests/web/sse/SSEManagerTest.kt`

---

### DASH-026: SSE Route Endpoints
**Priority**: P0  
**Time**: 2h  
**Dependencies**: DASH-025  
**Assigned To**: Backend Developer  
**Phase**: 3-SSE  
**Component**: Routes

**Description**: Create SSE endpoint routes for different event types

**Tasks**:
1. Create `web/routes/SSERoutes.kt`
2. Implement SSE endpoints:
   - GET /sse/tasks → task update events
   - GET /sse/index → index update events
   - GET /sse/metrics → metrics update events
   - GET /sse/all → all event types
3. Set up SSE response headers:
   - `Content-Type: text/event-stream`
   - `Cache-Control: no-cache`
   - `Connection: keep-alive`
4. Handle client reconnection
5. Write SSE route tests

**Acceptance Criteria**:
- All SSE endpoints functional
- Correct headers set
- Events stream continuously
- Reconnection works
- Response format correct
- Tests verify streaming

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/routes/SSERoutes.kt`
- `tests/web/routes/SSERoutesTest.kt`

---

### DASH-027: EventBus Integration
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-025  
**Assigned To**: Backend Developer  
**Phase**: 3-SSE  
**Component**: SSE

**Description**: Subscribe to EventBus and broadcast to SSE clients

**Tasks**:
1. Create `web/sse/EventBusSubscriber.kt`:
   - Subscribe to EventBus events
   - Convert domain events to SSE events
   - Generate HTML fragments for events
   - Broadcast to SSE clients
2. Handle event types:
   - TaskStatusChangedEvent → taskUpdated SSE
   - TaskCreatedEvent → taskCreated SSE
   - IndexProgressEvent → indexProgress SSE
   - MetricsUpdatedEvent → metricsUpdated SSE
   - AlertTriggeredEvent → alertTriggered SSE
3. Generate HTML fragments:
   - Task row HTML for table updates
   - Progress bar HTML for index
   - Metric card HTML for dashboard
   - Alert HTML for notifications
4. Add event filtering/routing
5. Write integration tests

**Acceptance Criteria**:
- EventBus events trigger SSE broadcasts
- HTML fragments generated correctly
- All subscribers receive events
- Event filtering works
- No event loss
- Tests verify integration

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/sse/EventBusSubscriber.kt`
- `src/main/kotlin/com/orchestrator/web/sse/FragmentGenerator.kt`
- `tests/web/sse/EventBusSubscriberTest.kt`

---

### DASH-028: HTMX SSE Integration
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-026, DASH-027  
**Assigned To**: Frontend Developer  
**Phase**: 3-SSE  
**Component**: UI

**Description**: Configure HTMX to handle SSE updates on client

**Tasks**:
1. Add HTMX SSE extension to pages:
   - `hx-ext="sse"`
   - `sse-connect="/sse/tasks"`
2. Configure SSE swap targets:
   - Tasks table: `sse-swap="taskUpdated"`
   - Index status: `sse-swap="indexProgress"`
   - Metrics: `sse-swap="metricsUpdated"`
   - Alerts: `sse-swap="alertTriggered"`
3. Implement out-of-band (OOB) swaps:
   - Multiple page updates from single event
   - `hx-swap-oob="true"` attribute
4. Add connection status indicator
5. Handle reconnection gracefully
6. Test SSE in browser

**Acceptance Criteria**:
- SSE connection established on page load
- Events update UI automatically
- OOB swaps work for multiple elements
- Connection status visible
- Reconnection automatic
- No console errors

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/rendering/PageLayout.kt`
- `src/main/resources/static/css/sse-status.css`

---

### DASH-029: Task Table Live Updates
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-028  
**Assigned To**: Frontend Developer  
**Phase**: 3-SSE  
**Component**: UI

**Description**: Implement live task table updates via SSE

**Tasks**:
1. Add SSE swap to task table:
   - `sse-swap="taskUpdated swap:outerHTML"`
   - Target: `#task-row-{id}`
2. Handle new task creation:
   - `sse-swap="taskCreated swap:afterbegin:#tasks-tbody"`
3. Handle task deletion:
   - `sse-swap="taskDeleted swap:delete:#task-row-{id}"`
4. Update row count in real-time
5. Add visual flash on update (optional)
6. Test live updates

**Acceptance Criteria**:
- Task updates reflected immediately
- New tasks appear at top
- Deleted tasks removed
- Row count updates
- No page flickering
- Performance good (<50ms swap)

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/components/DataTable.kt`
- `src/main/resources/static/css/animations.css`

---

### DASH-030: SSE Connection Status Indicator
**Priority**: P2  
**Time**: 2h  
**Dependencies**: DASH-028  
**Assigned To**: Frontend Developer  
**Phase**: 3-SSE  
**Component**: UI

**Description**: Create visual indicator for SSE connection status

**Tasks**:
1. Create `web/components/ConnectionStatus.kt`:
   - Green dot when connected
   - Yellow dot when reconnecting
   - Red dot when disconnected
   - Tooltip with details
2. Add to navigation bar or footer
3. Listen to SSE connection events:
   - `htmx:sseOpen` → connected
   - `htmx:sseError` → error
   - `htmx:sseClose` → disconnected
4. Show reconnection attempts
5. Style status indicator
6. Write component tests

**Acceptance Criteria**:
- Status indicator visible on all pages
- Color reflects connection state
- Tooltip shows helpful info
- Updates on connection changes
- Visually subtle but noticeable

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/ConnectionStatus.kt`
- `src/main/resources/static/js/sse-status.js`
- `src/main/resources/static/css/connection-status.css`

---

## Phase 4: Index Status Page (Week 5)

### DASH-031: Index Status Data Layer
**Priority**: P0  
**Time**: 2h  
**Dependencies**: None  
**Assigned To**: Backend Developer  
**Phase**: 4-Index  
**Component**: Domain

**Description**: Create DTOs and queries for index status page

**Tasks**:
1. Create `web/dto/IndexStatusDTO.kt`:
   - Total files count
   - Indexed files count
   - Pending files count
   - Failed files count
   - Last refresh timestamp
   - Index health status
2. Create `web/dto/FileStateDTO.kt`:
   - File path
   - Status (indexed, outdated, pending, error)
   - File size
   - Last modified
   - Chunk count
3. Query ContextModule for data
4. Add extension functions for mapping
5. Write DTO tests

**Acceptance Criteria**:
- DTOs map from ContextModule data
- All counts accurate
- File states correctly categorized
- Tests verify mappings

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/dto/IndexStatusDTO.kt`
- `src/main/kotlin/com/orchestrator/web/dto/FileStateDTO.kt`
- `tests/web/dto/IndexStatusDTOTest.kt`

---

### DASH-032: Index Status Page Route
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-031  
**Assigned To**: Backend Developer  
**Phase**: 4-Index  
**Component**: Routes

**Description**: Create index status page route

**Tasks**:
1. Create `web/routes/IndexRoutes.kt`
2. Implement GET /index route:
   - Query index status from ContextModule
   - Query provider health
   - Render full page
3. Add status dashboard summary
4. Add file browser section
5. Add admin action buttons
6. Write route tests

**Acceptance Criteria**:
- /index renders status page
- All metrics displayed correctly
- Provider health shown
- Admin actions available
- Response time <100ms
- Tests cover all scenarios

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/routes/IndexRoutes.kt`
- `src/main/kotlin/com/orchestrator/web/pages/IndexStatusPage.kt`
- `tests/web/routes/IndexRoutesTest.kt`

---

### DASH-033: Index Status Dashboard Component
**Priority**: P1  
**Time**: 4h  
**Dependencies**: DASH-032  
**Assigned To**: Frontend Developer  
**Phase**: 4-Index  
**Component**: UI

**Description**: Create dashboard showing index status and health

**Tasks**:
1. Create `web/components/IndexDashboard.kt`:
   - Status summary cards (total, indexed, pending, failed)
   - Provider health indicators
   - Last refresh timestamp
   - Index size (files, chunks, storage)
   - Performance metrics (query time)
2. Add visual status indicators:
   - Green: healthy
   - Yellow: degraded
   - Red: critical
3. Style dashboard cards
4. Make responsive (grid layout)
5. Add SSE updates for live data
6. Write component tests

**Acceptance Criteria**:
- Dashboard shows all key metrics
- Status colors accurate
- Layout responsive
- Live updates work
- Visually appealing
- Accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/IndexDashboard.kt`
- `src/main/kotlin/com/orchestrator/web/components/StatusCard.kt`

---

### DASH-034: File Browser Component
**Priority**: P1  
**Time**: 1 day  
**Dependencies**: DASH-032  
**Assigned To**: Frontend Developer  
**Phase**: 4-Index  
**Component**: UI

**Description**: Create file browser to view indexed files

**Tasks**:
1. Create `web/components/FileBrowser.kt`:
   - Table of files with columns (path, status, size, modified, chunks)
   - Search/filter by filename
   - Filter by status
   - Filter by extension
   - Sort by columns
   - Pagination
2. Add HTMX attributes for dynamic loading
3. Add file detail view (click to expand)
4. Show chunk count per file
5. Add status badges (indexed, outdated, error)
6. Style file browser
7. Write component tests

**Acceptance Criteria**:
- All indexed files listed
- Search works
- Filters work
- Sorting works
- Pagination works
- Detail view shows chunks
- Mobile responsive

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/FileBrowser.kt`
- `src/main/kotlin/com/orchestrator/web/components/FileRow.kt`

---

### DASH-035: File Browser Fragment Route
**Priority**: P1  
**Time**: 2h  
**Dependencies**: DASH-034  
**Assigned To**: Backend Developer  
**Phase**: 4-Index  
**Component**: Routes

**Description**: Create fragment route for file browser updates

**Tasks**:
1. Extend `web/routes/IndexRoutes.kt`
2. Implement GET /index/files route:
   - Parse query parameters (search, filters, sort, pagination)
   - Query file_state from database
   - Render table body fragment
3. Support filters:
   - `status`: indexed, outdated, pending, error
   - `extension`: .kt, .md, .sql, etc.
   - `search`: filename search
4. Optimize query performance
5. Write route tests

**Acceptance Criteria**:
- /index/files returns HTML fragment
- All filters work correctly
- Search works (LIKE query)
- Sorting works
- Pagination works
- Response time <50ms for 1000 files

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/routes/IndexRoutes.kt`

---

### DASH-036: Index Admin Actions
**Priority**: P1  
**Time**: 4h  
**Dependencies**: DASH-032  
**Assigned To**: Backend Developer  
**Phase**: 4-Index  
**Component**: Routes

**Description**: Implement admin action endpoints for index management

**Tasks**:
1. Extend `web/routes/IndexRoutes.kt`
2. Implement POST /index/refresh:
   - Trigger index refresh via ContextModule
   - Return 202 Accepted
   - Return progress HTML fragment
3. Implement POST /index/rebuild:
   - Trigger full rebuild via ContextModule
   - Show confirmation modal first
   - Return 202 Accepted
   - Return progress HTML fragment
4. Implement POST /index/optimize:
   - Trigger database optimization
   - Return status HTML
5. Add CSRF protection
6. Add rate limiting
7. Write action tests

**Acceptance Criteria**:
- Refresh action triggers correctly
- Rebuild action requires confirmation
- Optimize action works
- Progress updates via SSE
- Actions idempotent
- Rate limiting prevents abuse
- Tests verify all actions

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/routes/IndexRoutes.kt`

---

### DASH-037: Index Progress Component
**Priority**: P1  
**Time**: 3h  
**Dependencies**: DASH-036  
**Assigned To**: Frontend Developer  
**Phase**: 4-Index  
**Component**: UI

**Description**: Create progress indicator for index operations

**Tasks**:
1. Create `web/components/ProgressBar.kt`:
   - Progress bar with percentage
   - Status message
   - Files processed count
   - Estimated time remaining (optional)
   - Cancel button (optional)
2. Style progress bar
3. Add SSE updates:
   - `sse-swap="indexProgress"`
   - Update progress bar in real-time
4. Add completion notification
5. Add error handling
6. Write component tests

**Acceptance Criteria**:
- Progress bar shows during operations
- Updates smoothly via SSE
- Percentage accurate
- Status messages helpful
- Completion notification shown
- Error handling works

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/ProgressBar.kt`
- `src/main/resources/static/css/progress.css`

---

### DASH-038: Provider Health Component
**Priority**: P2  
**Time**: 2h  
**Dependencies**: DASH-033  
**Assigned To**: Frontend Developer  
**Phase**: 4-Index  
**Component**: UI

**Description**: Create component showing provider health status

**Tasks**:
1. Create `web/components/ProviderHealth.kt`:
   - List of providers (semantic, symbol, full-text, etc.)
   - Health status for each (healthy, degraded, down)
   - Response time for each
   - Last check timestamp
   - Refresh button
2. Add visual health indicators
3. Style provider list
4. Add SSE updates for real-time health
5. Write component tests

**Acceptance Criteria**:
- All providers listed
- Health status accurate
- Response times shown
- Visual indicators clear
- Real-time updates work
- Accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/ProviderHealth.kt`

---

## Phase 5: Metrics Dashboard (Week 6)

### DASH-039: Metrics Data Layer
**Priority**: P0  
**Time**: 3h  
**Dependencies**: None  
**Assigned To**: Backend Developer  
**Phase**: 5-Metrics  
**Component**: Domain

**Description**: Create DTOs and queries for metrics dashboard

**Tasks**:
1. Create `web/dto/MetricsSummaryDTO.kt`:
   - Total tokens used
   - Token savings
   - Average task completion time
   - Average agent response time
   - Overall success rate
   - Active alerts count
2. Create `web/dto/ChartDataDTO.kt`:
   - Time series data
   - Labels and values
   - Multiple series support
3. Query MetricsModule for data
4. Aggregate data for time ranges
5. Write DTO tests

**Acceptance Criteria**:
- DTOs map from MetricsModule
- Time series data formatted correctly
- Aggregations accurate
- Tests verify calculations

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/dto/MetricsSummaryDTO.kt`
- `src/main/kotlin/com/orchestrator/web/dto/ChartDataDTO.kt`
- `tests/web/dto/MetricsDTOTest.kt`

---

### DASH-040: Metrics Page Route
**Priority**: P0  
**Time**: 3h  
**Dependencies**: DASH-039  
**Assigned To**: Backend Developer  
**Phase**: 5-Metrics  
**Component**: Routes

**Description**: Create metrics dashboard page route

**Tasks**:
1. Create `web/routes/MetricsRoutes.kt`
2. Implement GET /metrics route:
   - Query metrics summary
   - Query token usage over time
   - Query performance metrics
   - Query decision analytics
   - Render full page
3. Support date range filtering
4. Support agent/task type filtering
5. Write route tests

**Acceptance Criteria**:
- /metrics renders dashboard page
- All metrics displayed
- Date range filter works
- Response time <100ms
- Tests cover all scenarios

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/routes/MetricsRoutes.kt`
- `src/main/kotlin/com/orchestrator/web/pages/MetricsPage.kt`
- `tests/web/routes/MetricsRoutesTest.kt`

---

### DASH-041: Metrics Summary Cards
**Priority**: P1  
**Time**: 3h  
**Dependencies**: DASH-040  
**Assigned To**: Frontend Developer  
**Phase**: 5-Metrics  
**Component**: UI

**Description**: Create summary metric cards for dashboard

**Tasks**:
1. Create `web/components/MetricCard.kt`:
   - Metric label
   - Metric value (large, prominent)
   - Trend indicator (up/down arrow)
   - Percentage change
   - Sparkline chart (optional)
2. Create cards for key metrics:
   - Total tokens used
   - Token savings
   - Average task time
   - Success rate
3. Style metric cards
4. Make responsive (grid layout)
5. Add SSE updates
6. Write component tests

**Acceptance Criteria**:
- Cards show key metrics
- Values formatted nicely (commas, units)
- Trend indicators accurate
- Layout responsive
- Live updates work
- Visually appealing

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/MetricCard.kt`
- `src/main/kotlin/com/orchestrator/web/utils/NumberFormatters.kt`

---

### DASH-042: Token Usage Chart
**Priority**: P1  
**Time**: 4h  
**Dependencies**: DASH-040  
**Assigned To**: Frontend Developer  
**Phase**: 5-Metrics  
**Component**: UI

**Description**: Create token usage chart with Chart.js or canvas

**Tasks**:
1. Choose charting approach:
   - **Option A**: Chart.js (~60KB, feature-rich)
   - **Option B**: Custom canvas charts (lightweight, custom)
2. Create `web/components/TokenChart.kt`:
   - Line chart showing token usage over time
   - Multiple series (input, output, total)
   - Legend
   - Tooltips
   - Responsive
3. Render chart data as JSON
4. Initialize chart on page load
5. Add date range selector
6. Style chart container
7. Write component tests

**Acceptance Criteria**:
- Chart renders correctly
- Multiple series visible
- Tooltips show values
- Responsive (mobile friendly)
- Date range filter works
- Chart updates on filter change

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/TokenChart.kt`
- `src/main/resources/static/js/chart-init.js`
- Add Chart.js to static files (if chosen)

---

### DASH-043: Performance Charts
**Priority**: P1  
**Time**: 4h  
**Dependencies**: DASH-040  
**Assigned To**: Frontend Developer  
**Phase**: 5-Metrics  
**Component**: UI

**Description**: Create performance metric charts

**Tasks**:
1. Create `web/components/PerformanceChart.kt`:
   - Average task completion time (line chart)
   - Average agent response time (line chart)
   - Success rate over time (area chart)
   - Bottleneck distribution (bar chart)
2. Render charts with chosen library
3. Add tooltips and legends
4. Make responsive
5. Style chart containers
6. Write component tests

**Acceptance Criteria**:
- All charts render correctly
- Data accurate
- Charts responsive
- Tooltips helpful
- Visual design consistent

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/PerformanceChart.kt`

---

### DASH-044: Decision Analytics Display
**Priority**: P2  
**Time**: 3h  
**Dependencies**: DASH-040  
**Assigned To**: Frontend Developer  
**Phase**: 5-Metrics  
**Component**: UI

**Description**: Create visualization for decision analytics

**Tasks**:
1. Create `web/components/DecisionAnalytics.kt`:
   - Routing accuracy gauge
   - Strategy effectiveness table
   - Confidence calibration chart
   - Optimal strategy recommendations
2. Add visual elements:
   - Gauge chart for accuracy
   - Table with color coding
   - Scatter plot for calibration
3. Style analytics section
4. Write component tests

**Acceptance Criteria**:
- Analytics displayed clearly
- Gauge chart works
- Table sortable
- Recommendations actionable
- Visual design consistent

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/DecisionAnalytics.kt`
- `src/main/kotlin/com/orchestrator/web/components/GaugeChart.kt`

---

### DASH-045: Metrics Export Functionality
**Priority**: P2  
**Time**: 2h  
**Dependencies**: DASH-040  
**Assigned To**: Backend Developer  
**Phase**: 5-Metrics  
**Component**: Routes

**Description**: Implement metrics export in CSV and JSON formats

**Tasks**:
1. Extend `web/routes/MetricsRoutes.kt`
2. Implement GET /metrics/export:
   - Support format parameter (csv, json)
   - Support date range parameters
   - Query metrics data
   - Format as CSV or JSON
   - Set appropriate headers
   - Trigger download
3. Implement CSV formatter
4. Implement JSON formatter
5. Write export tests

**Acceptance Criteria**:
- CSV export downloads correctly
- JSON export downloads correctly
- Date range filtering works
- File names include timestamp
- Headers set for download
- Tests verify formats

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/routes/MetricsRoutes.kt`
- `src/main/kotlin/com/orchestrator/web/utils/ExportFormatters.kt` (new)

---

### DASH-046: Date Range Picker
**Priority**: P2  
**Time**: 3h  
**Dependencies**: DASH-040  
**Assigned To**: Frontend Developer  
**Phase**: 5-Metrics  
**Component**: UI

**Description**: Create date range picker for metrics filtering

**Tasks**:
1. Create `web/components/DateRangePicker.kt`:
   - Start date input
   - End date input
   - Quick presets (Last 7 days, Last 30 days, etc.)
   - Apply button
   - Clear button
2. Add HTMX attributes:
   - `hx-get` to metrics fragments
   - `hx-include` for date inputs
   - `hx-target` for chart containers
3. Add date validation
4. Style date picker
5. Make mobile friendly
6. Write component tests

**Acceptance Criteria**:
- Date inputs work correctly
- Quick presets functional
- Validation prevents invalid ranges
- Apply updates charts via HTMX
- Clear resets to defaults
- Mobile friendly

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/DateRangePicker.kt`
- `src/main/resources/static/css/date-picker.css`

---

## Phase 6: Polish and Optimization (Week 7-8)

### DASH-047: Loading States and Indicators
**Priority**: P1  
**Time**: 3h  
**Dependencies**: All previous UI tasks  
**Assigned To**: Frontend Developer  
**Phase**: 6-Polish  
**Component**: UI

**Description**: Add loading indicators for all async operations

**Tasks**:
1. Create `web/components/LoadingIndicator.kt`:
   - Spinner component
   - Skeleton screens for tables
   - Progress bars for operations
   - Inline loading states
2. Add HTMX indicators:
   - `hx-indicator="#loading"`
   - Show/hide automatically
3. Add loading overlay for full page operations
4. Style loading states
5. Add accessibility (aria-busy)
6. Write component tests

**Acceptance Criteria**:
- Loading shown during all requests
- Spinner appears immediately
- Skeleton screens smooth
- Indicators accessible
- Visual feedback clear

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/LoadingIndicator.kt`
- `src/main/resources/static/css/loading.css`

---

### DASH-048: Error Handling and Notifications
**Priority**: P1  
**Time**: 3h  
**Dependencies**: All previous tasks  
**Assigned To**: Frontend Developer  
**Phase**: 6-Polish  
**Component**: UI

**Description**: Implement comprehensive error handling and user notifications

**Tasks**:
1. Create `web/components/Notification.kt`:
   - Toast notification component
   - Success, error, warning, info types
   - Auto-dismiss with timeout
   - Manual dismiss button
   - Stack multiple notifications
2. Add to page layout
3. Trigger notifications:
   - HTMX errors (404, 500, etc.)
   - Operation success/failure
   - SSE errors
4. Style notifications
5. Add accessibility (aria-live)
6. Write component tests

**Acceptance Criteria**:
- Notifications appear on actions
- Auto-dismiss works
- Manual dismiss works
- Multiple notifications stack
- ARIA live region present
- Accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/Notification.kt`
- `src/main/resources/static/js/notifications.js`
- `src/main/resources/static/css/notifications.css`

---

### DASH-049: Dark Mode Implementation
**Priority**: P2  
**Time**: 4h  
**Dependencies**: DASH-006  
**Assigned To**: Frontend Developer  
**Phase**: 6-Polish  
**Component**: UI

**Description**: Implement dark mode with toggle

**Tasks**:
1. Create dark mode CSS variables
2. Create `web/components/DarkModeToggle.kt`:
   - Toggle switch in navigation
   - Save preference to localStorage
   - Apply on page load
3. Add dark mode styles:
   - Invert colors
   - Adjust contrast
   - Update chart colors
   - Update syntax highlighting
4. Add smooth transition
5. Test in both modes
6. Write component tests

**Acceptance Criteria**:
- Dark mode toggle visible
- Preference persists
- All pages support dark mode
- Charts readable in dark mode
- Smooth transition between modes
- Accessible

**Files to Create**:
- `src/main/kotlin/com/orchestrator/web/components/DarkModeToggle.kt`
- `src/main/resources/static/css/dark-mode.css`
- `src/main/resources/static/js/dark-mode.js`

---

### DASH-050: Keyboard Shortcuts
**Priority**: P2  
**Time**: 3h  
**Dependencies**: All page tasks  
**Assigned To**: Frontend Developer  
**Phase**: 6-Polish  
**Component**: UI

**Description**: Implement keyboard shortcuts for common actions

**Tasks**:
1. Create keyboard shortcut handler:
   - `/` - Focus search
   - `Escape` - Close modal
   - `Enter` - Open task detail
   - `r` - Refresh index
   - `?` - Show shortcuts help
2. Create shortcuts help modal
3. Add visual indicators for shortcuts
4. Handle conflicts (ignore when typing)
5. Add to documentation
6. Write shortcut tests

**Acceptance Criteria**:
- All shortcuts work
- Help modal shows all shortcuts
- Shortcuts don't interfere with typing
- Visual hints for shortcuts
- Accessible

**Files to Create**:
- `src/main/resources/static/js/shortcuts.js`
- `src/main/kotlin/com/orchestrator/web/components/ShortcutsHelp.kt`

---

### DASH-051: Mobile Responsive Optimization
**Priority**: P1  
**Time**: 1 day  
**Dependencies**: All UI tasks  
**Assigned To**: Frontend Developer  
**Phase**: 6-Polish  
**Component**: UI

**Description**: Optimize all pages for mobile devices

**Tasks**:
1. Review all pages on mobile (320px-768px)
2. Fix layout issues:
   - Tables → horizontal scroll or cards
   - Navigation → hamburger menu
   - Charts → full width
   - Forms → stacked layout
3. Add touch-friendly interactions
4. Optimize tap targets (44px minimum)
5. Test on actual devices
6. Fix any mobile-specific bugs

**Acceptance Criteria**:
- All pages usable on mobile
- No horizontal scroll (except tables)
- Touch targets large enough
- Forms easy to fill
- Charts readable
- Performance good

**Files to Update**:
- All CSS files with media queries
- Layout components

---

### DASH-052: Performance Optimization
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: All previous tasks  
**Assigned To**: Backend Developer + Frontend Developer  
**Phase**: 6-Polish  
**Component**: All

**Description**: Optimize performance to meet targets

**Tasks**:
Backend:
1. Add database query caching
2. Optimize slow queries
3. Add HTTP caching headers
4. Minimize HTML output size
5. Enable compression (gzip)
6. Profile and optimize hot paths

Frontend:
7. Minimize CSS (if custom)
8. Add critical CSS inline
9. Lazy load images
10. Defer non-critical JS
11. Optimize chart rendering
12. Reduce DOM manipulation

**Acceptance Criteria**:
- Server response <100ms p95
- HTMX swap <50ms
- Page load <1s on 3G
- Lighthouse score >90
- No memory leaks
- Performance tests pass

**Files to Update**:
- Various files across codebase

---

### DASH-053: Accessibility Audit and Fixes
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: All UI tasks  
**Assigned To**: Frontend Developer + QA  
**Phase**: 6-Polish  
**Component**: UI

**Description**: Ensure WCAG 2.1 AA compliance

**Tasks**:
1. Run accessibility audit tools:
   - axe DevTools
   - WAVE
   - Lighthouse
2. Fix identified issues:
   - Missing alt text
   - Insufficient contrast
   - Missing ARIA labels
   - Keyboard navigation gaps
   - Focus indicators
3. Test with screen readers:
   - NVDA (Windows)
   - VoiceOver (macOS)
4. Add skip links
5. Test keyboard navigation
6. Document accessibility features

**Acceptance Criteria**:
- WCAG 2.1 AA compliant
- No critical accessibility errors
- Screen reader usable
- Keyboard navigation complete
- Focus visible
- Accessibility statement added

**Files to Update**:
- All HTML components
- `docs/ACCESSIBILITY.md` (new)

---

### DASH-054: Browser Compatibility Testing
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: All UI tasks  
**Assigned To**: QA  
**Phase**: 6-Polish  
**Component**: All

**Description**: Test and fix issues across browsers

**Tasks**:
1. Test on browsers:
   - Chrome (latest)
   - Firefox (latest)
   - Safari (latest)
   - Edge (latest)
2. Test on operating systems:
   - Windows 10/11
   - macOS
   - Linux
3. Fix browser-specific issues
4. Add browser compatibility notes
5. Test HTMX functionality
6. Test SSE functionality

**Acceptance Criteria**:
- Works on all major browsers
- SSE works on all browsers
- HTMX works on all browsers
- Visual consistency across browsers
- No JavaScript errors
- Compatibility documented

**Files to Update**:
- Various CSS/JS files
- `docs/BROWSER_SUPPORT.md` (new)

---

## Phase 7: Testing and Documentation (Week 9-10)

### DASH-055: Unit Test Coverage
**Priority**: P1  
**Time**: 3 days  
**Dependencies**: All implementation tasks  
**Assigned To**: Backend Developer + Frontend Developer  
**Phase**: 7-Testing  
**Component**: All

**Description**: Achieve >80% unit test coverage

**Tasks**:
1. Write unit tests for:
   - All route handlers
   - All components
   - All DTOs and mappers
   - All utilities
   - SSE infrastructure
2. Use Ktor test framework
3. Mock dependencies appropriately
4. Test edge cases
5. Run coverage analysis
6. Fix untested code

**Acceptance Criteria**:
- Overall coverage >80%
- Critical paths >90%
- All public APIs tested
- Edge cases covered
- Tests fast (<5min total)

**Files to Create**:
- Many test files across codebase

---

### DASH-056: Integration Tests
**Priority**: P1  
**Time**: 3 days  
**Dependencies**: All implementation tasks  
**Assigned To**: Backend Developer + QA  
**Phase**: 7-Testing  
**Component**: All

**Description**: Create integration tests for full workflows

**Tasks**:
1. Test end-to-end workflows:
   - View tasks → filter → detail view
   - Index refresh → watch progress → completion
   - View metrics → change date range → export
2. Test SSE integration:
   - Connect SSE → receive events → UI updates
3. Test HTMX integration:
   - Click action → request → fragment swap
4. Test with real database
5. Test concurrent requests
6. Write integration test suite

**Acceptance Criteria**:
- All major workflows tested
- SSE tested end-to-end
- HTMX tested end-to-end
- Tests run in CI
- Tests isolated (no shared state)

**Files to Create**:
- `tests/integration/web/` (multiple test files)

---

### DASH-057: End-to-End Browser Tests
**Priority**: P1  
**Time**: 3 days  
**Dependencies**: All implementation tasks  
**Assigned To**: QA  
**Phase**: 7-Testing  
**Component**: All

**Description**: Create E2E tests with Playwright or Selenium

**Tasks**:
1. Set up E2E test framework (Playwright recommended)
2. Write E2E tests:
   - Navigate to tasks page
   - Filter and sort tasks
   - Open task detail
   - View Mermaid diagram
   - Navigate to index page
   - Trigger index refresh
   - Watch progress updates
   - Navigate to metrics page
   - Change date range
   - Export metrics
3. Test SSE updates in browser
4. Test HTMX interactions
5. Run in CI

**Acceptance Criteria**:
- All critical flows tested
- Tests run headless in CI
- Screenshots on failure
- Tests stable (no flakiness)
- All pages covered

**Files to Create**:
- `tests/e2e/web/` (test files)
- `playwright.config.js` or equivalent

---

### DASH-058: Load and Performance Tests
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: DASH-052  
**Assigned To**: Backend Developer + QA  
**Phase**: 7-Testing  
**Component**: All

**Description**: Conduct load testing to verify performance targets

**Tasks**:
1. Set up load testing (k6, Gatling, or Artillery)
2. Create load test scenarios:
   - View tasks page (100 concurrent users)
   - Filter and sort tasks
   - SSE connections (50 concurrent)
   - View metrics page
3. Run baseline tests
4. Identify bottlenecks
5. Optimize if needed
6. Re-run tests
7. Document performance results

**Acceptance Criteria**:
- Server handles 100 concurrent users
- Response times meet targets (<100ms p95)
- SSE handles 50 concurrent connections
- No memory leaks
- Performance report generated

**Files to Create**:
- `tests/load/web/` (load test scripts)
- `docs/PERFORMANCE_RESULTS.md`

---

### DASH-059: User Documentation
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: All implementation tasks  
**Assigned To**: Technical Writer / Developer  
**Phase**: 7-Testing  
**Component**: Documentation

**Description**: Create comprehensive user documentation

**Tasks**:
1. Write user guide sections:
   - Getting started
   - Tasks page guide
   - Index management guide
   - Metrics dashboard guide
   - Keyboard shortcuts
   - Troubleshooting
2. Add screenshots for all pages
3. Document all features
4. Add FAQ section
5. Create video tutorials (optional)
6. Review and proofread

**Acceptance Criteria**:
- All features documented
- Clear step-by-step guides
- Screenshots helpful
- FAQ answers common questions
- Easy to navigate

**Files to Create**:
- `docs/web-dashboard/USER_GUIDE.md`
- `docs/web-dashboard/TROUBLESHOOTING.md`
- `docs/web-dashboard/FAQ.md`

---

### DASH-060: Developer Documentation
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: All implementation tasks  
**Assigned To**: Backend Developer  
**Phase**: 7-Testing  
**Component**: Documentation

**Description**: Create documentation for developers

**Tasks**:
1. Document architecture decisions
2. Document HTMX patterns used
3. Document SSE implementation
4. Document component structure
5. Document testing strategy
6. Add code examples
7. Document extension points
8. Create onboarding guide

**Acceptance Criteria**:
- Architecture clearly explained
- HTMX patterns documented
- SSE implementation documented
- New developers can contribute
- Extension points clear

**Files to Create**:
- `docs/web-dashboard/DEVELOPER_GUIDE.md`
- `docs/web-dashboard/HTMX_PATTERNS.md`
- `docs/web-dashboard/SSE_GUIDE.md`

---

### DASH-061: API Documentation
**Priority**: P2  
**Time**: 1 day  
**Dependencies**: All route tasks  
**Assigned To**: Backend Developer  
**Phase**: 7-Testing  
**Component**: Documentation

**Description**: Document all web dashboard endpoints

**Tasks**:
1. Document all routes:
   - Page routes
   - Fragment routes
   - SSE routes
   - API routes
2. Document query parameters
3. Document response formats
4. Add request/response examples
5. Document error responses
6. Create OpenAPI spec (optional)

**Acceptance Criteria**:
- All endpoints documented
- Examples provided
- Error cases covered
- Parameters explained
- Response formats clear

**Files to Create**:
- `docs/web-dashboard/API_REFERENCE.md`

---

## Phase 8: Deployment and Launch (Week 11-12)

### DASH-062: Production Configuration
**Priority**: P0  
**Time**: 2 days  
**Dependencies**: All implementation tasks  
**Assigned To**: DevOps / Backend Developer  
**Phase**: 8-Deployment  
**Component**: Configuration

**Description**: Configure web dashboard for production deployment

**Tasks**:
1. Create production configuration:
   - Port configuration
   - SSL/TLS settings
   - CORS configuration
   - Cache headers
   - Security headers
2. Add environment-specific configs
3. Configure logging for production
4. Set up secrets management
5. Add health check monitoring
6. Document configuration options
7. Test production config locally

**Acceptance Criteria**:
- Production config complete
- SSL/TLS configured
- Security headers set
- Secrets managed securely
- Configuration documented
- Tested locally

**Files to Create**:
- `src/main/resources/web-server-production.conf`
- `docs/PRODUCTION_CONFIGURATION.md`

---

### DASH-063: Security Hardening
**Priority**: P0  
**Time**: 2 days  
**Dependencies**: All implementation tasks  
**Assigned To**: Backend Developer  
**Phase**: 8-Deployment  
**Component**: Security

**Description**: Implement security best practices

**Tasks**:
1. Add security headers:
   - Content-Security-Policy
   - X-Frame-Options
   - X-Content-Type-Options
   - Referrer-Policy
   - Permissions-Policy
2. Implement CSRF protection
3. Add rate limiting
4. Implement request validation
5. Sanitize HTML output
6. Configure CORS restrictively
7. Run security scan
8. Fix identified issues

**Acceptance Criteria**:
- All security headers present
- CSRF protection works
- Rate limiting prevents abuse
- Input validation comprehensive
- XSS prevented
- Security scan clean

**Files to Update**:
- `src/main/kotlin/com/orchestrator/web/WebServer.kt`
- `src/main/kotlin/com/orchestrator/web/middleware/SecurityMiddleware.kt` (new)

---

### DASH-064: CI/CD Pipeline Updates
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: All testing tasks  
**Assigned To**: DevOps  
**Phase**: 8-Deployment  
**Component**: CI/CD

**Description**: Update CI/CD pipeline for web dashboard

**Tasks**:
1. Add web tests to CI:
   - Unit tests
   - Integration tests
   - E2E tests
2. Add build verification
3. Add artifact creation
4. Add deployment stage
5. Configure test reporting
6. Add coverage reporting
7. Test pipeline end-to-end

**Acceptance Criteria**:
- All tests run in CI
- Build artifacts created
- Deployment automated
- Test reports generated
- Coverage tracked
- Pipeline reliable

**Files to Update**:
- `.github/workflows/ci.yml`
- `.github/workflows/deploy.yml`

---

### DASH-065: Monitoring and Alerting Setup
**Priority**: P1  
**Time**: 2 days  
**Dependencies**: DASH-062  
**Assigned To**: DevOps  
**Phase**: 8-Deployment  
**Component**: Monitoring

**Description**: Set up monitoring for web dashboard

**Tasks**:
1. Configure metrics collection:
   - Request count
   - Response times
   - Error rates
   - SSE connection count
2. Set up dashboards (Grafana)
3. Configure alerts:
   - High error rate
   - Slow response times
   - SSE connection issues
4. Set up log aggregation
5. Configure uptime monitoring
6. Document monitoring setup

**Acceptance Criteria**:
- Metrics collected
- Dashboards created
- Alerts configured
- Logs aggregated
- Uptime monitored
- Documentation complete

**Files to Create**:
- `config/monitoring/web-dashboard-grafana.json`
- `docs/MONITORING.md`

---

### DASH-066: Load Balancer Configuration (Optional)
**Priority**: P3  
**Time**: 1 day  
**Dependencies**: DASH-062  
**Assigned To**: DevOps  
**Phase**: 8-Deployment  
**Component**: Infrastructure

**Description**: Configure load balancer for multiple instances (if needed)

**Tasks**:
1. Configure load balancer (nginx/HAProxy)
2. Set up sticky sessions for SSE
3. Configure health checks
4. Set up SSL termination
5. Configure connection limits
6. Test failover
7. Document configuration

**Acceptance Criteria**:
- Load balancer configured
- Sticky sessions work for SSE
- Health checks functional
- SSL termination works
- Failover tested
- Documentation complete

**Files to Create**:
- `config/nginx/web-dashboard.conf`
- `docs/LOAD_BALANCER.md`

---

### DASH-067: Backup and Recovery Procedures
**Priority**: P1  
**Time**: 1 day  
**Dependencies**: DASH-062  
**Assigned To**: DevOps  
**Phase**: 8-Deployment  
**Component**: Operations

**Description**: Document backup and recovery procedures

**Tasks**:
1. Document backup procedures:
   - Database backups
   - Configuration backups
   - Static files backups
2. Document recovery procedures:
   - Database restore
   - Configuration restore
   - Disaster recovery
3. Test backup procedures
4. Test recovery procedures
5. Document RTO and RPO
6. Create runbook

**Acceptance Criteria**:
- Backup procedures documented
- Recovery procedures documented
- Procedures tested successfully
- RTO/RPO defined
- Runbook complete

**Files to Create**:
- `docs/BACKUP_RECOVERY.md`
- `docs/DISASTER_RECOVERY_RUNBOOK.md`

---

### DASH-068: Launch Checklist and Go-Live
**Priority**: P0  
**Time**: 1 day  
**Dependencies**: All previous tasks  
**Assigned To**: Project Manager + Team  
**Phase**: 8-Deployment  
**Component**: Launch

**Description**: Execute production launch

**Tasks**:
1. Create launch checklist:
   - All tests passing
   - Documentation complete
   - Security scan clean
   - Performance verified
   - Monitoring configured
   - Backups tested
2. Conduct pre-launch review
3. Schedule maintenance window
4. Execute deployment
5. Verify deployment
6. Monitor post-launch
7. Communicate launch
8. Conduct retrospective

**Acceptance Criteria**:
- All checklist items complete
- Deployment successful
- No critical issues
- Monitoring shows healthy system
- Team debriefed

**Files to Create**:
- `docs/LAUNCH_CHECKLIST.md`
- `docs/POST_LAUNCH_REPORT.md`

---

### DASH-069: Post-Launch Support and Bug Fixes
**Priority**: P0  
**Time**: Ongoing (1 week)  
**Dependencies**: DASH-068  
**Assigned To**: Entire Team  
**Phase**: 8-Deployment  
**Component**: Support

**Description**: Provide immediate post-launch support

**Tasks**:
1. Monitor system 24/7 for first 48 hours
2. Respond to user feedback
3. Triage and fix critical bugs
4. Track issues in issue tracker
5. Deploy hotfixes as needed
6. Update documentation based on feedback
7. Conduct daily standups
8. Collect metrics for success evaluation

**Acceptance Criteria**:
- Critical bugs fixed within 4 hours
- User feedback tracked
- System stable
- Metrics collected
- Team aware of issues

---

### DASH-070: Performance Tuning Based on Production Data
**Priority**: P1  
**Time**: 1 week  
**Dependencies**: DASH-069  
**Assigned To**: Backend Developer  
**Phase**: 8-Deployment  
**Component**: Optimization

**Description**: Optimize based on real production metrics

**Tasks**:
1. Analyze production metrics
2. Identify performance bottlenecks
3. Optimize slow queries
4. Tune cache settings
5. Adjust SSE configuration
6. Optimize asset delivery
7. Measure improvements
8. Document optimizations

**Acceptance Criteria**:
- Performance targets met
- Bottlenecks resolved
- Metrics improved
- Optimizations documented

---

## Summary

### Phase Overview

| Phase | Duration | Tasks | Focus |
|-------|----------|-------|-------|
| Phase 0: Foundation | Week 1 | 10 | Setup, routing, static files, HTML rendering |
| Phase 1: Tasks Page | Week 2 | 8 | Tasks list, DataTable, search, filters |
| Phase 2: Task Detail | Week 3 | 6 | Detail view, proposals, decisions, Mermaid |
| Phase 3: SSE Real-Time | Week 4 | 6 | SSE infrastructure, EventBus, live updates |
| Phase 4: Index Status | Week 5 | 8 | Index dashboard, file browser, admin actions |
| Phase 5: Metrics Dashboard | Week 6 | 8 | Charts, analytics, export functionality |
| Phase 6: Polish | Week 7-8 | 8 | Loading states, error handling, mobile, dark mode |
| Phase 7: Testing & Docs | Week 9-10 | 7 | Unit tests, integration tests, E2E, documentation |
| Phase 8: Deployment | Week 11-12 | 10 | Security, monitoring, deployment, launch |

**Total**: 75 tasks over 10-12 weeks

---

## Technology Decisions Summary

### Why HTMX?
- ✅ **Zero build step** - just include one JS file
- ✅ **Server-driven** - HTML rendered on server
- ✅ **Progressive enhancement** - works without JS
- ✅ **Small size** - ~14KB minified
- ✅ **Simple** - HTML attributes instead of JS code

### Why SSE over WebSockets?
- ✅ **One-way sufficient** - server→client updates only
- ✅ **Simpler** - no bidirectional protocol complexity
- ✅ **HTTP/2 friendly** - leverages existing infrastructure
- ✅ **Auto-reconnect** - browser handles reconnection
- ✅ **HTTP semantics** - works with standard HTTP tools

### Why kotlinx.html?
- ✅ **Type-safe** - compile-time HTML validation
- ✅ **Kotlin-native** - no template language to learn
- ✅ **Composable** - build components easily
- ✅ **Fast** - direct rendering, no parsing
- ✅ **Testable** - unit test HTML generation

### Why Separate Ports (8080 vs 8081)?
- ✅ **Isolation** - MCP and Web traffic don't interfere
- ✅ **Security** - different policies for each
- ✅ **Clarity** - clear separation of concerns
- ✅ **Scalability** - can scale independently

---

## Critical Path

Tasks that directly block launch (must complete in order):

```
DASH-001 → DASH-002 → DASH-003 → DASH-005 → DASH-009 → DASH-011 → 
DASH-012 → DASH-013 → DASH-019 → DASH-025 → DASH-026 → DASH-027 → 
DASH-031 → DASH-032 → DASH-039 → DASH-040 → DASH-055 → DASH-062 → 
DASH-063 → DASH-068
```

**Critical Path Duration**: ~8-9 weeks

Any delays in critical path tasks directly delay launch.

---

## Risk Management

### High-Risk Tasks

1. **DASH-023: Mermaid Integration**
   - Risk: Complex diagram generation logic
   - Mitigation: Start with simple flows, iterate

2. **DASH-027: EventBus Integration**
   - Risk: Event loss or duplicate events
   - Mitigation: Thorough testing, event ID tracking

3. **DASH-052: Performance Optimization**
   - Risk: May require significant refactoring
   - Mitigation: Profile early, optimize incrementally

4. **DASH-057: E2E Browser Tests**
   - Risk: Flaky tests, maintenance burden
   - Mitigation: Use stable selectors, retry logic

5. **DASH-063: Security Hardening**
   - Risk: May break existing functionality
   - Mitigation: Test thoroughly, gradual rollout

---

## Success Metrics

### Technical Metrics
- **Server Response Time**: <100ms p95
- **HTMX Swap Time**: <50ms
- **Page Load Time**: <1s on 3G
- **SSE Connection Uptime**: >99%
- **Lighthouse Score**: >90
- **Code Coverage**: >80%

### User Experience Metrics
- **Time to First Interaction**: <2s
- **Task List Load Time**: <500ms
- **Search Response Time**: <300ms
- **Chart Render Time**: <200ms
- **Mobile Usability**: 100% (Lighthouse)

### Quality Metrics
- **Zero Critical Bugs** at launch
- **WCAG 2.1 AA Compliant**
- **Zero Build Step** (verified)
- **Browser Support**: Chrome, Firefox, Safari, Edge (latest)

---

## Dependencies and Prerequisites

### External Dependencies
- Ktor 2.3.7+
- HTMX 1.9.10+ (~14KB)
- Mermaid.js 10.6.1+ (~500KB)
- Pico CSS 2.0.6+ (optional, ~10KB)
- Chart.js (optional, ~60KB)

### Internal Dependencies
- Existing TaskRepository
- Existing MetricsModule
- Existing ContextModule
- Existing EventBus
- Existing DuckDB schema

### Team Skills Required
- Kotlin development
- Ktor framework
- HTML/CSS
- HTMX patterns
- SSE/EventSource
- DuckDB/SQL

---

## Resource Allocation

### Team Composition
- **1 Backend Developer** (full-time) - 12 weeks
- **1 Frontend Developer** (full-time) - 12 weeks
- **1 QA Engineer** (full-time) - 4 weeks (weeks 9-12)
- **1 DevOps Engineer** (part-time, 50%) - 2 weeks (weeks 11-12)
- **1 Technical Writer** (part-time, 25%) - 1 week (week 9)

### Estimated Effort
- **Backend**: ~50 person-days
- **Frontend**: ~50 person-days
- **QA**: ~20 person-days
- **DevOps**: ~5 person-days
- **Documentation**: ~2 person-days

**Total**: ~127 person-days over 12 weeks

---

## Communication Plan

### Daily
- Standup (15 minutes)
- Slack/communication tool for coordination
- Update task status in tracker

### Weekly
- Sprint planning (1 hour)
- Technical review (1 hour)
- Demo to stakeholders (30 minutes)

### Bi-weekly
- Retrospective (1 hour)
- Architecture review (1 hour)
- Risk assessment (30 minutes)

### Milestone Reviews
- End of Phase 0: Foundation complete
- End of Phase 3: Real-time updates working
- End of Phase 5: All pages complete
- End of Phase 7: Testing complete
- Launch: Go-live review

---

## Definition of Done

A task is "done" when:

1. ✅ Code written and committed
2. ✅ Unit tests written and passing (>80% coverage)
3. ✅ Code reviewed and approved
4. ✅ Integration tests passing (if applicable)
5. ✅ Browser tested (Chrome, Firefox)
6. ✅ Mobile tested (responsive)
7. ✅ Accessibility verified (no critical issues)
8. ✅ Documentation updated
9. ✅ Deployed to staging and verified
10. ✅ Acceptance criteria met

---

## Appendix: Quick Reference

### Key URLs
- Home: `http://localhost:8081/`
- Tasks: `http://localhost:8081/tasks`
- Index: `http://localhost:8081/index`
- Metrics: `http://localhost:8081/metrics`
- Health: `http://localhost:8081/health`
- SSE Tasks: `http://localhost:8081/sse/tasks`

### Key Technologies
- **Backend**: Ktor, kotlinx.html, DuckDB
- **Frontend**: HTMX, Mermaid.js, Pico CSS (optional)
- **Real-time**: Server-Sent Events (SSE)
- **Build**: Gradle (no npm/webpack)

### Performance Targets
- Server response: <100ms p95
- HTMX swap: <50ms
- Page load: <1s on 3G
- SSE latency: <100ms

### Browser Support
- Chrome (latest)
- Firefox (latest)
- Safari (latest)
- Edge (latest)
- Mobile browsers (iOS Safari, Chrome Android)

---

## Document Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-10-18 | System | Initial implementation plan |

---

**End of Implementation Plan**
